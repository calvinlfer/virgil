package io.kaizensolutions.virgil.internal

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.{BatchType => _, _}
import io.kaizensolutions.virgil.configuration.{ExecutionAttributes, PageState}
import io.kaizensolutions.virgil.internal.Proofs._
import io.kaizensolutions.virgil._
import zio._
import zio.stream._

import scala.jdk.CollectionConverters._

/**
 * CQLExecutorImpl is a ZIO based client for the Apache Cassandra Java Driver
 * that provides ZIO and ZStream abstractions over the Datastax Java driver. We
 * consider CQLExecutor to be the interpreter of [[CQL[A]]] expressions.
 *
 * @param underlyingSession
 *   is the underlying Datastax Java driver session
 */
private[virgil] class CQLExecutorImpl(underlyingSession: CqlSession) extends CQLExecutor {
  override def execute[A](in: CQL[A]): Stream[Throwable, A] =
    in.cqlType match {
      case m: CQLType.Mutation =>
        ZStream.fromEffect(executeMutation(m, in.executionAttributes).asInstanceOf[Task[A]])

      case b: CQLType.Batch =>
        ZStream.fromEffect(executeBatch(b, in.executionAttributes).asInstanceOf[Task[A]])

      case q @ CQLType.Query(_, _, pullMode) =>
        pullMode match {
          case PullMode.TakeUpto(n) if n <= 1 =>
            ZStream.fromEffectOption(executeSingleResultQuery(q, in.executionAttributes).some)

          case PullMode.TakeUpto(n) =>
            executeGeneralQuery(q, in.executionAttributes).take(n)

          case PullMode.All =>
            executeGeneralQuery(q, in.executionAttributes)
        }
    }

  override def executeMutation(in: CQL[MutationResult]): Task[MutationResult] =
    in.cqlType match {
      case mutation: CQLType.Mutation =>
        executeMutation(mutation, in.executionAttributes)

      case batch: CQLType.Batch =>
        executeBatch(batch, in.executionAttributes)

      case CQLType.Query(_, _, _) =>
        sys.error("Cannot perform a query using executeMutation")
    }

  override def executePage[A](in: CQL[A], pageState: Option[PageState])(implicit
    ev: A =:!= MutationResult
  ): Task[Paged[A]] = {
    val _ = ev
    in.cqlType match {
      case _: CQLType.Mutation =>
        sys.error("Mutations cannot be used with page queries")

      case CQLType.Batch(_, _) =>
        sys.error("Batch Mutations cannot be used with page queries")

      case q @ CQLType.Query(_, _, _) =>
        fetchSinglePage(q, pageState, in.executionAttributes)
          .asInstanceOf[Task[Paged[A]]]
    }
  }

  private def fetchSinglePage[A](
    q: CQLType.Query[A],
    pageState: Option[PageState],
    attr: ExecutionAttributes
  ): Task[Paged[A]] = {
    val (queryString, bindMarkers) = CqlStatementRenderer.render(q)
    for {
      boundStatement        <- buildStatement(queryString, bindMarkers, attr)
      reader                 = q.reader
      driverPageState        = pageState.map(_.underlying).orNull
      boundStatementWithPage = boundStatement.setPagingState(driverPageState)
      rp                    <- selectPage(boundStatementWithPage)
      (results, nextPage)    = rp
      chunksToOutput        <- results.mapM(row => ZIO.effect(reader.decode(row)))
    } yield Paged(chunksToOutput, nextPage)
  }

  private def executeMutation(m: CQLType.Mutation, config: ExecutionAttributes): Task[MutationResult] =
    for {
      statement <- buildMutation(m, config)
      result    <- executeAction(statement)
    } yield MutationResult.make(result.wasApplied())

  private def executeBatch(m: CQLType.Batch, config: ExecutionAttributes): Task[MutationResult] =
    ZIO
      .foreach(m.mutations)(buildMutation(_))
      .mapEffect { statementsToBatch =>
        val batch = BatchStatement
          .builder(m.batchType.toDriver)
          .addStatements(statementsToBatch.toSeq.asJava)

        config.configureBatch(batch).build()
      }
      .flatMap(executeAction)
      .map(r => MutationResult.make(r.wasApplied()))

  private def executeGeneralQuery[Output](
    input: CQLType.Query[Output],
    config: ExecutionAttributes
  ): ZStream[Any, Throwable, Output] = {
    val (queryString, bindMarkers) = CqlStatementRenderer.render(input)
    for {
      boundStatement <- ZStream.fromEffect(buildStatement(queryString, bindMarkers, config))
      reader          = input.reader
      element <- select(boundStatement).mapChunksM { chunk =>
                   chunk.mapM(row => ZIO.effect(reader.decode(row)))
                 }
    } yield element
  }

  private def executeSingleResultQuery[Output](
    input: CQLType.Query[Output],
    config: ExecutionAttributes
  ): ZIO[Any, Throwable, Option[Output]] = {
    val (queryString, bindMarkers) = CqlStatementRenderer.render(input)
    for {
      boundStatement <- buildStatement(queryString, bindMarkers, config)
      reader          = input.reader
      optRow         <- selectFirst(boundStatement)
      element        <- Task(optRow.map(reader.decode))
    } yield element
  }

  private def selectFirst(query: Statement[_]): Task[Option[Row]] =
    executeAction(query).map(resultSet => Option(resultSet.one()))

  private def buildMutation(
    in: CQLType.Mutation,
    attr: ExecutionAttributes = ExecutionAttributes.default
  ): Task[BatchableStatement[_]] = {
    val (queryString, bindMarkers) = CqlStatementRenderer.render(in)

    if (bindMarkers.isEmpty) Task.succeed(SimpleStatement.newInstance(queryString))
    else buildStatement(queryString, bindMarkers, attr)
  }

  private def prepare(query: String): Task[PreparedStatement] =
    ZIO.fromCompletionStage(underlyingSession.prepareAsync(query))

  private def executeAction(query: Statement[_]): Task[AsyncResultSet] =
    ZIO.fromCompletionStage(underlyingSession.executeAsync(query))

  private def select(query: Statement[_]): ZStream[Any, Throwable, Row] = {
    val initialEffect = ZIO.fromCompletionStage(underlyingSession.executeAsync(query))

    /*
     * pull describes how to pull one page of data from the underlying driver
     * and keeping track of how to pull the next page of data via the ref.
     *
     * Note that failing with a None indicates that there is no more data to
     * pull. Failing with Some(throwable) indicates that there was an error
     *
     * pull does has to choose __one__ of the following strategies once a page
     * of data is retrieved:
     *
     *   - The retrieved page tells us that there are more paged to be retrieved
     *     so we update the ref to the next page before emitting the current
     *     page's results
     *
     *   - The retrieved page tells us that there are no more pages to be
     *     retrieved and the retrieved page has some results so we emit the
     *     current page's results and then update the ref to signal that the
     *     next pull should stop
     *
     *   - The retrieved page tells us that there are no more pages to be pages
     *     to retrieved and the current page has no results so we stop
     *     immediately
     *
     * @param ref
     *   holds the next set of results to be read which involve fetching data
     *   from the database
     *
     * @return
     */
    def pull(ref: Ref[IO[Option[Throwable], AsyncResultSet]]): IO[Option[Throwable], Chunk[Row]] =
      for {
        io <- ref.get
        rs <- io
        _ <- rs match {
               case _ if rs.hasMorePages =>
                 ref.set(Task.fromCompletionStage(rs.fetchNextPage()).mapError(Option(_)))

               case _ if rs.remaining() > 0 =>
                 ref.set(IO.fail(None))

               case _ =>
                 IO.fail(None)
             }
      } yield Chunk.fromIterable(rs.currentPage().asScala)

    Stream(
      Ref
        .makeManaged(initialEffect.mapError(Option(_)))
        .map(pull)
    )
  }

  private def selectPage(queryConfiguredWithPageState: Statement[_]): Task[(Chunk[Row], Option[PageState])] =
    executeAction(queryConfiguredWithPageState).map { rs =>
      val currentRows = Chunk.fromIterable(rs.currentPage().asScala)
      if (rs.hasMorePages) {
        val pageState = PageState.fromDriver(rs.getExecutionInfo.getSafePagingState)
        (currentRows, Option(pageState))
      } else (currentRows, None)
    }

  private def buildStatement(
    queryString: String,
    columns: BindMarkers,
    config: ExecutionAttributes
  ): Task[BoundStatement] =
    prepare(queryString).mapEffect { preparedStatement =>
      val result: BoundStatementBuilder = {
        val initial = preparedStatement.boundStatementBuilder()
        val boundColumns = columns.underlying.foldLeft(initial) { case (accBuilder, (colName, column)) =>
          column.write.encodeByFieldName(
            structure = accBuilder,
            fieldName = colName.name,
            value = column.value
          )
        }
        // Configure bound statement with the common execution attributes
        // https://docs.datastax.com/en/developer/java-driver/4.13/manual/core/statements/
        config.configure(boundColumns)
      }
      result.build()
    }
}
