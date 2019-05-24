package tech.cryptonomic.conseil.tezos

import com.typesafe.scalalogging.LazyLogging
import tech.cryptonomic.conseil.tezos.TezosTypes._
import tech.cryptonomic.conseil.util.{CryptoUtil, JsonUtil}
import tech.cryptonomic.conseil.util.CryptoUtil.KeyStore
import tech.cryptonomic.conseil.util.JsonUtil.{fromJson, JsonString => JS}
import tech.cryptonomic.conseil.config.{BatchFetchConfiguration, SodiumConfiguration}
import tech.cryptonomic.conseil.tezos.TezosTypes.Lenses._
import tech.cryptonomic.conseil.tezos.michelson.JsonToMichelson.convert
import tech.cryptonomic.conseil.tezos.michelson.dto.{MichelsonElement, MichelsonInstruction, MichelsonSchema}
import tech.cryptonomic.conseil.tezos.michelson.parser.JsonParser.Parser

import cats._
import cats.implicits._
import cats.data.Kleisli
import scala.{Stream => _}
import scala.concurrent.{ExecutionContext, Future}
import scala.math.max
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

import cats.effect.IO
import fs2.Stream

object TezosNodeOperator {
  /**
    * Output of operation signing.
    * @param bytes      Signed bytes of the transaction
    * @param signature  The actual signature
    */
  final case class SignedOperationGroup(bytes: Array[Byte], signature: String)

  /**
    * Result of a successfully sent operation
    * @param results          Results of operation application
    * @param operationGroupID Operation group ID
    */
  final case class OperationResult(results: AppliedOperation, operationGroupID: String)

  /**
    * Given a contiguous valus range, creates sub-ranges of max the given size
    * @param pageSize how big a part is allowed to be
    * @param range a range of contiguous values to partition into (possibly) smaller parts
    * @return an iterator over the part, which are themselves `Ranges`
    */
    def partitionRanges(pageSize: Int)(range: Range.Inclusive): Iterator[Range.Inclusive] =
      range.grouped(pageSize)
        .filterNot(_.isEmpty)
        .map(subRange => subRange.head to subRange.last)

    val isGenesis = (data: BlockData) => data.header.level == 0

}

/**
  * Operations run against Tezos nodes, mainly used for collecting chain data for later entry into a database.
  *
  * @param node               Tezos node connection object
  * @param network            Which Tezos network to go against
  * @param batchConf          configuration for batched download of node data
  * @param fetchFutureContext thread context for async operations
  */
class TezosNodeOperator(val node: TezosRPCInterface, val network: String, batchConf: BatchFetchConfiguration)(implicit val fetchFutureContext: ExecutionContext)
  extends LazyLogging
  with BlocksDataFetchers
  with AccountsDataFetchers {
  import TezosNodeOperator.isGenesis
  import batchConf.{accountConcurrencyLevel, blockOperationsConcurrencyLevel, blockPageSize}

  override val fetchConcurrency = blockOperationsConcurrencyLevel
  override val accountsFetchConcurrency = accountConcurrencyLevel

  //use this alias to make signatures easier to read and kept in-sync
  type BlockFetchingResults = List[(BlockAction, List[AccountId])]
  type PaginatedBlocksResults = (Iterator[Future[BlockFetchingResults]], Int)
  type PaginatedAccountResults = (Iterator[Future[List[BlockTagged[Map[AccountId, Account]]]]], Int)

  //introduced to simplify signatures
  type BallotBlock = (Block, List[Voting.Ballot])
  type BakerBlock = (Block, List[Voting.BakerRolls])

  //use this handle to override the operations injected references for testing purposes
  protected lazy val operations: ApiOperations = ApiOperations

  /**
    * A sum type representing the actions that can happen with a block read from the node,
    * both as new ones and previous ones detected during a fork.
    */
  sealed trait BlockAction extends Product with Serializable {
    def block: Block
  }
  /** block was invalid and should be restored as the valid one */
  case class RevalidateBlock(block: Block) extends BlockAction
  /** block wasn't present and should be added and made the valid one */
  case class WriteAndMakeValidBlock(block: Block) extends BlockAction
  /** block needs to be stored */
  case class WriteBlock(block: Block) extends BlockAction

  /**
    * Fetches a specific account for a given block.
    * @param blockHash  Hash of given block
    * @param accountId  Account ID
    * @return           The account
    */
  def getAccountForBlock(blockHash: BlockHash, accountId: AccountId): Future[Account] =
    node.runAsyncGetQuery(network, s"blocks/${blockHash.value}/context/contracts/${accountId.id}")
      .map(fromJson[Account])

  /**
    * Fetches the manager of a specific account for a given block.
    * @param blockHash  Hash of given block
    * @param accountId  Account ID
    * @return           The account
    */
  def getAccountManagerForBlock(blockHash: BlockHash, accountId: AccountId): Future[ManagerKey] =
    node.runAsyncGetQuery(network, s"blocks/${blockHash.value}/context/contracts/${accountId.id}/manager_key")
      .map(fromJson[ManagerKey])

  /**
    * Fetches all accounts for a given block.
    * @param blockHash  Hash of given block.
    * @return           Accounts
    */
  def getAllAccountsForBlock(blockHash: BlockHash): Future[Map[AccountId, Account]] =
    for {
      jsonEncodedAccounts <- node.runAsyncGetQuery(network, s"blocks/${blockHash.value}/context/contracts")
      accountIds = fromJson[List[String]](jsonEncodedAccounts).map(AccountId)
      accounts <- getAccountsForBlock(accountIds, blockHash)
    } yield accounts

  /**
    * Fetches the accounts identified by id, lazily paginating the results
    *
    * @param accountIds the ids
    * @param blockHash  the block storing the accounts, the head block if not specified
    * @return           the list of accounts wrapped in a [[Future]], indexed by AccountId
    */
  def getPaginatedAccountsForBlock(accountIds: List[AccountId], blockHash: BlockHash = blockHeadHash): Iterator[Future[Map[AccountId, Account]]] =
    partitionAccountIds(accountIds).map(ids => getAccountsForBlock(ids, blockHash))

  /**
    * Fetches the accounts identified by id
    *P
    * @param accountIds the ids
    * @param blockHash  the block storing the accounts, the head block if not specified
    * @return           the list of accounts wrapped in a [[Future]], indexed by AccountId
    */
  def getAccountsForBlock(accountIds: List[AccountId], blockHash: BlockHash = blockHeadHash): Future[Map[AccountId, Account]] = {
    import TezosOptics.Accounts.{scriptLens, storageLens}
    import tech.cryptonomic.conseil.generic.chain.DataFetcher.fetch

    implicit val fetcherInstance = accountFetcher(blockHash)

    val fetchedAccounts: Future[List[(AccountId, Option[Account])]] =
      fetch[AccountId, Option[Account], Future, List, Throwable].run(accountIds)

    def parseMichelsonScripts(account: Account): Account = {
      val scriptAlter = scriptLens.modify(toMichelsonScript[MichelsonSchema])
      val storageAlter = storageLens.modify(toMichelsonScript[MichelsonInstruction])

      (scriptAlter compose storageAlter)(account)
    }

    fetchedAccounts.map(
      indexedAccounts =>
        indexedAccounts.collect {
          case (accountId, Some(account)) => accountId -> parseMichelsonScripts(account)
        }.toMap
    )
  }
  /**
    * Get accounts for all the identifiers passed-in with the corresponding block
    * @param accountsBlocksIndex a map from unique id to the [latest] block reference
    * @return         Accounts with their corresponding block data
    */
  def getAccountsForBlocks(accountsBlocksIndex: Map[AccountId, BlockReference]): PaginatedAccountResults = {
    import TezosTypes.Syntax._

    def notifyAnyLostIds(missing: Set[AccountId]) =
      if (missing.nonEmpty) logger.warn("The following account keys were not found querying the {} node: {}", network, missing.map(_.id).mkString("\n", ",", "\n"))

    //uses the index to collect together BlockAccounts matching the same block
    def groupByLatestBlock(data: Map[AccountId, Account]): List[BlockTagged[Map[AccountId, Account]]] =
      data.groupBy {
        case (id, _) => accountsBlocksIndex(id)
      }.map {
        case ((hash, level), accounts) => accounts.taggedWithBlock(hash, level)
      }.toList

    //fetch accounts by requested ids and group them together with corresponding blocks
    val pages = getPaginatedAccountsForBlock(accountsBlocksIndex.keys.toList) map {
      futureMap =>
        futureMap
         .andThen {
            case Success(accountsMap) =>
              notifyAnyLostIds(accountsBlocksIndex.keySet -- accountsMap.keySet)
            case Failure(err) =>
              val showSomeIds = accountsBlocksIndex.keys.take(30).map(_.id).mkString("", ",", if (accountsBlocksIndex.size > 30) "..." else "")
              logger.error(s"Could not get accounts' data for ids ${showSomeIds}", err)
          }.map(groupByLatestBlock)
    }

    (pages, accountsBlocksIndex.size)
  }

  /**
    * Fetches operations for a block, without waiting for the result
    * @param blockHash Hash of the block
    * @return          The `Future` list of operations
    */
  def getAllOperationsForBlock(block: BlockData): Future[List[OperationsGroup]] = {
    import JsonDecoders.Circe.decodeLiftingTo
    import JsonDecoders.Circe.Operations._
    import tech.cryptonomic.conseil.util.JsonUtil.adaptManagerPubkeyField

    //parse json, and try to convert to objects, converting failures to a failed `Future`
    //we could later improve by "accumulating" all errors in a single failed future, with `decodeAccumulating`
    def decodeOperations(json: String) =
      decodeLiftingTo[Future, List[List[OperationsGroup]]](adaptManagerPubkeyField(JS.sanitize(json)))
        .map(_.flatten)

    if (isGenesis(block))
      Future.successful(List.empty) //This is a workaround for the Tezos node returning a 404 error when asked for the operations or accounts of the genesis blog, which seems like a bug.
    else
      node.runAsyncGetQuery(network, s"blocks/${block.hash.value}/operations")
        .flatMap(decodeOperations)

  }

  /**
    * Fetches accountIds for a block, without waiting for the result
    * @param blockHash Hash of the block
    * @return          The `Future` list of accountIds
    */
  def getAllAccountIdsForBlock(block: BlockData): Future[List[AccountId]] =
    if (isGenesis(block))
      Future.successful(List.empty) //This is a workaround for the Tezos node returning a 404 error when asked for the operations or accounts of the genesis blog, which seems like a bug.
    else
      node
        .runAsyncGetQuery(network, s"blocks/${block.hash.value}/operations")
        .map(accountIdsJsonDecode.run)

  /** Fetches votes information for the block */
  def getCurrentVotesForBlock(block: BlockData, offset: Option[Offset] = None): Future[CurrentVotes] =
    if (isGenesis(block))
      CurrentVotes.empty.pure[Future]
    else {
      import JsonDecoders.Circe._

      val offsetString = offset.map(_.toString).getOrElse("")
      val hashString = block.hash.value

      val fetchCurrentQuorum =
        node.runAsyncGetQuery(network, s"blocks/$hashString~$offsetString/votes/current_quorum") flatMap { json =>
          decodeLiftingTo[Future, Option[Int]](json)
        }

      val fetchCurrentProposal =
        node.runAsyncGetQuery(network, s"blocks/$hashString~$offsetString/votes/current_proposal") flatMap { json =>
          decodeLiftingTo[Future, Option[ProtocolId]](json)
        }

      (fetchCurrentQuorum, fetchCurrentProposal).mapN(CurrentVotes.apply)
    }

  /** Fetches detailed data for voting associated to the passed-in blocks */
  def getVotingDetails(blocks: List[Block]): Future[(List[Voting.Proposal], List[BakerBlock], List[BallotBlock])] = {
    import tech.cryptonomic.conseil.generic.chain.DataFetcher.fetch

    //adapt the proposal protocols result to include the block
    val fetchProposals =
      fetch[Block, List[ProtocolId], Future, List, Throwable]
        .map {
          proposalsList => proposalsList.map {
            case (block, protocols) => Voting.Proposal(protocols, block)
          }
        }

    val fetchBakers =
      fetch[Block, List[Voting.BakerRolls], Future, List, Throwable]

    val fetchBallots =
      fetch[Block, List[Voting.Ballot], Future, List, Throwable]


    /* combine the three kleisli operations to return a tuple of the results
     * and then run the composition on the input blocks
     */
    (fetchProposals, fetchBakers, fetchBallots).tupled.run(blocks.filterNot(b => isGenesis(b.data)))
  }

  /**
    * Fetches a single block from the chain, without waiting for the result
    * @param hash      Hash of the block
    * @return          the block data wrapped in a `Future`
    */
  def getBlock(hash: BlockHash, offset: Option[Offset] = None): Future[Block] = {
    import JsonDecoders.Circe.decodeLiftingTo
    import JsonDecoders.Circe.Blocks._

    val offsetString = offset.map(_.toString).getOrElse("")

    //starts immediately
    val fetchBlock =
      node.runAsyncGetQuery(network, s"blocks/${hash.value}~$offsetString") flatMap { json =>
        decodeLiftingTo[Future, BlockData](JS.sanitize(json))
      }

    for {
      block <- fetchBlock
      ops <- getAllOperationsForBlock(block)
      votes <- getCurrentVotesForBlock(block)
    } yield Block(block, ops, votes)
  }

  /**
    * Gets the block head.
    * @return Block head
    */
  def getBlockHead(): Future[Block]= {
    getBlock(blockHeadHash)
  }

  /**
    * Given a level range, creates sub-ranges of max the given size
    * @param levels a range of levels to partition into (possibly) smaller parts
    * @param pageSize how big a part is allowed to be
    * @return an iterator over the part, which are themselves `Ranges`
    */
  def partitionBlocksRanges(levels: Range.Inclusive): Iterator[Range.Inclusive] =
    TezosNodeOperator.partitionRanges(blockPageSize)(levels)

  /**
    * Given a list of ids, creates sub-lists of max the given size
    * @param accouuntIDs a list of ids to partition into (possibly) smaller parts
    * @param pageSize how big a part is allowed to be
    * @return an iterator over the part, which are themselves ids
    */
  def partitionAccountIds(accountsIDs: List[AccountId]): Iterator[List[AccountId]] =
  TezosNodeOperator.partitionRanges(blockPageSize)(Range.inclusive(0, accountsIDs.size - 1)) map {
      range => accountsIDs.take(range.end + 1).drop(range.start)
    }

  /**
    * Gets all blocks from the head down to the oldest block not already in the database.
    * @return Blocks and Account hashes involved
    */
  def getBlocksNotInDatabase(followFork: Boolean = false): Future[PaginatedBlocksResults] =
    for {
      maxLevel <- operations.fetchMaxLevel
      blockHead <- getBlockHead()
      headLevel = blockHead.data.header.level
      headHash = blockHead.data.hash
    } yield {
      val bootstrapping = maxLevel == -1
      if (maxLevel < headLevel) {
        //got something to load
        if (bootstrapping) logger.warn("There were apparently no blocks in the database. Downloading the whole chain..")
        else logger.info("I found the new block head at level {}, the currently stored max is {}. I'll fetch the missing {} blocks.", headLevel, maxLevel, headLevel - maxLevel)
        val pagedResults = partitionBlocksRanges((maxLevel + 1) to headLevel).map(
          page => getBlocks((headHash, headLevel), page, followFork)
        )
        val minLevel = if (bootstrapping) 1 else maxLevel
        (pagedResults, headLevel - minLevel)
      } else {
        logger.info("No new blocks to fetch from the network")
        (Iterator.empty, 0)
      }
    }

  /**
    * Gets last `depth` blocks.
    * @param depth      Number of latest block to fetch, `None` to get all
    * @param headHash   Hash of a block from which to start, None to start from a real head
    * @return           Blocks and Account hashes involved
    */
  def getLatestBlocks(depth: Option[Int] = None, headHash: Option[BlockHash] = None, followFork: Boolean = false): Future[PaginatedBlocksResults] =
    headHash
      .map(getBlock(_))
      .getOrElse(getBlockHead())
      .map {
        maxHead =>
          val headLevel = maxHead.data.header.level
          val headHash = maxHead.data.hash
          val minLevel = depth.fold(1)(d => max(1, headLevel - d + 1))
          val pagedResults = partitionBlocksRanges(minLevel to headLevel).map(
            page => getBlocks((headHash, headLevel), page, followFork)
          )
          (pagedResults, headLevel - minLevel + 1)
      }

  /*
   * If the currently stored block of highest level is not the same returned by the node, reload that
   * and all the missing predecessors on the fork, with actions to re-sync conseil data with the blockchain
   *
   * We start looking at headHash ~ maxOffset and backtrack to increasing offsets up to the deepest level stored still disagreeing
   * with the current chain from the node
   *
   * @param headBlockHash the hash of the current chain head block
   * @param maxLevelOffset the offset from the head that should be already stored, yet diverges from the current level on chain
   */
  private def getForkedBlocks(headBlockHash: BlockHash, maxLevelOffset: Int): Future[BlockFetchingResults] = {
    //read the latest stored top-level and the corresponding one from the current chain
    val highestLevelFromChain = getBlock(headBlockHash, Some(maxLevelOffset))//chain block
    val highestLevelOnConseil = operations.fetchLatestBlock() //stored block

    //compare the results and in case read the missing data from the fork
    val chainForkedSection: Future[List[BlockAction]] = Apply[Future].map2(highestLevelFromChain, highestLevelOnConseil) {
      case (remote, Some(stored)) if remote.data.header.level != stored.level =>
        //better stop the process than to risk corrupting conseil's database
        logger.error(
          """Loading the latest stored block and the corresponding expected block from the remote node returned a mismatched block level
             | Conseil stored block: {}
             | The Node returned block: {}
          """.stripMargin,
          stored,
          remote.data
        )
        Future.failed(new IllegalStateException("Fork detection found inconsistent levels in corresponding blocks"))
      case (remote, Some(stored)) if remote.data.hash.value != stored.hash =>
        followFork(remote)
      case (remote, None) =>
        logger.warn("There's no latest block stored on Conseil, corresponding to level {}. Trying to recover from the remote node", remote.data.header.level)
        followFork(remote) //the local block for that level is missing... this shouldn't actually happen!
      case _ =>
        List.empty.pure[Future] //no additional action to take
    }.flatten

    //add account references to the fetched data
    chainForkedSection.flatMap(_ traverse addAccountReferences)
  }

  /* extracts account references for each action, if useful
   * i.e. when the action assumes that accounts are stored already, there's
   * no need to fetch those references again
   */
  private def addAccountReferences(action: BlockAction): Future[(BlockAction, List[AccountId])] =
    action match {
      case revalidation @ RevalidateBlock(_) => Future.successful(revalidation -> List.empty)
      case write => getAllAccountIdsForBlock(write.block.data) map (write -> _)
    }

  /**
    * Given the blockchain network and the hash of the block where the fork was detected,
    * collect the list of blocks missed during the fork in reverse order.
    *
    * Note: We switch from future to IO to utilize the fs2 Stream library effectively,
    * for concision and efficiency purposes. We may want to consider using IO instead
    * of Future in general.
    *
    * @param hash Hash of the block where the fork was detected
    * @return Future of List of Blocks that were missed during the Fork, in reverse order
    */
  def followFork(missingBlock: Block): Future[List[BlockAction]] = {

    import tech.cryptonomic.conseil.util.EffectConversionUtil._

    logger.info(s"An inconsistent block was detected at level ${missingBlock.data.header.level}, with hash ${missingBlock.data.hash.value}, possibly from a forked branch, I'm syncing ...")

    implicit val db = operations.dbHandle

    /* just for clarity
     * Kleisli[IO, A, B] is a typed wrapper to a function of type: A => IO[B]
     * hence enabling a lot of powerful combinatory operations to simplify
     * coding expressions
     */

    //load a block for an offset
    val getBlockIO = Kleisli[IO, Option[Int], Block]{
      (maybeOffset) => futureToIO(getBlock(missingBlock.data.hash, maybeOffset))
    }

    //check if the block is on db
    val blockExists = Kleisli[IO, Block, Boolean]{
      block => runToIO(TezosDatabaseOperations.blockExists(block.data.hash))
    }

    //check if the block is on the invalidated list
    val blockHasBeenInvalidated = Kleisli[IO, Block, Boolean]{
      block => runToIO(TezosDatabaseOperations.blockIsInInvalidatedState(block.data.hash))
    }

    //given the same input, computes both outputs
    val predicate: Kleisli[IO, Block, (Boolean, Boolean)] =
      blockExists &&& blockHasBeenInvalidated

    //returns both the input block and the computed predicates in a 3-tuple
    val extractPredicatesForBlock: Kleisli[IO, Block, (Boolean, Boolean, Block)] =
      predicate.tapWith{ case (block, (exists, invalidated)) => (exists, invalidated, block)}

    /*
     * Given an offset from the block where the hash was detected, figure out if we need to collect that
     * block and/or perform a write action to our database.
     */
    def collectBlocksUntilValidExists(offset: Int): IO[Option[(BlockAction, Int)]] = {

      //step that evaluates if the process should continue, in which case it gives back the result with the next offset
      val evaluateConditions: ((Boolean, Boolean, Block)) => Option[(BlockAction, Int)] = {
        case (exists, invalidated, block) =>

          lazy val reachedValid = exists && !invalidated
          lazy val invalidYetMissing = !exists && invalidated
          lazy val needRevalidation = exists && invalidated

          logger.debug(s"""evaluating forking logic for the need to get another level deeper
                  | examined level ${block.data.header.level}
                  | block hash is ${block.data.hash.value}
                  | block is on the blocks table? $exists
                  | block is invalidated? $invalidated
                  | hence: reachedValid: $reachedValid; invalidYetMissing: $invalidYetMissing; needRevalidation: $needRevalidation
                  |""".stripMargin)

          if (reachedValid)
            None
          else if (invalidYetMissing) {
            //this case should be impossible, you should not have a block that doesn't exists, while being invalid
            logger.error("While following a forked chain I stepped into an invalid block that's not stored in Conseil {}", block)
            None
          }
          else if (needRevalidation)  {
            Some(RevalidateBlock(block), offset + 1)
          }
          else {
            Some(WriteAndMakeValidBlock(block), offset + 1)
          }
      }

      //compose all the steps and map the output
      val evaluateNextStep = (getBlockIO andThen extractPredicatesForBlock).map(evaluateConditions)
      //apply the whole computation to get the result
      evaluateNextStep(Some(offset))

    }

    /*
     * Create IO Stream of the Forked Blocks, as well as the action to perform with said blocks
     * Given a block, you can either need to update the invalidatedBlocks table, or write a new block
     * to the InvalidatedBlocks table database and collect said block to be stored by the function.
     */
    val blockStream: Stream[IO, BlockAction] = Stream.unfoldEval(1)(collectBlocksUntilValidExists)
    // turn blockStream to Future[List[BlockWithAction]], after adding the originally missing block as head of the result
    blockStream.compile
      .toList
      .map(actions => WriteAndMakeValidBlock(missingBlock) :: actions)
      .unsafeToFuture()
  }

  def getBlocks(
    reference: (BlockHash, Int),
    levelRange: Range.Inclusive,
    followFork: Boolean
  ): Future[BlockFetchingResults] = {

    val blocksFromRange = getBlocks(reference, levelRange)

    val maxOffset = levelRange.end - levelRange.start + 1
    val blocksFromFork =
      if (followFork && levelRange.start > 0) getForkedBlocks(reference._1, maxOffset)
      else List.empty.pure[Future]

    for {
      range <- blocksFromRange
      fork <- blocksFromFork
    } yield (range ++ fork)
  }

  /**
    * Gets block from Tezos Blockchains, as well as their associated operation, from minLevel to maxLevel.
    * @param reference Hash and level of a known block
    * @param levelRange a range of levels to load
    * @return the async list of blocks with relative account ids touched in the operations
    */
  private def getBlocks(
    reference: (BlockHash, Int),
    levelRange: Range.Inclusive
  ): Future[BlockFetchingResults] = {
    import tech.cryptonomic.conseil.generic.chain.DataFetcher.{fetch, fetchMerge}

    val (hashRef, levelRef) = reference
    require(levelRange.start >= 0 && levelRange.end <= levelRef)
    val offsets = levelRange.map(lvl => levelRef - lvl).toList

    implicit val blockFetcher = blocksFetcher(hashRef)

    //read the separate parts of voting and merge the results
    val proposalsStateFetch =
      fetchMerge(currentQuorumFetcher, currentProposalFetcher)(CurrentVotes.apply)

    def parseMichelsonScripts(block: Block): Block = {
      val codeAlter = codeLens.modify(toMichelsonScript[MichelsonSchema])
      val storageAlter = storageLens.modify(toMichelsonScript[MichelsonInstruction])
      val parametersAlter = parametersLens.modify(toMichelsonScript[MichelsonInstruction])

      (codeAlter compose storageAlter compose parametersAlter)(block)
    }

    //Gets blocks data for the requested offsets and associates the operations and account hashes available involved in said operations
    //Special care is taken for the genesis block (level = 0) that doesn't have operations defined, we use empty data for it
    for {
      fetchedBlocksData <- fetch[Offset, BlockData, Future, List, Throwable].run(offsets)
      blockHashes = fetchedBlocksData.collect{ case (offset, block) if !isGenesis(block) => block.hash }
      fetchedOperationsWithAccounts <- fetch[BlockHash, (List[OperationsGroup], List[AccountId]), Future, List, Throwable].run(blockHashes)
      proposalsState <- proposalsStateFetch.run(blockHashes)
    } yield {
      val operationalDataMap = fetchedOperationsWithAccounts.map{ case (hash, (ops, accounts)) => (hash, (ops, accounts))}.toMap
      val proposalsMap = proposalsState.toMap
      fetchedBlocksData.map {
        case (offset, md) =>
          val (ops, accs) = if (isGenesis(md)) (List.empty, List.empty) else operationalDataMap(md.hash)
          val votes = proposalsMap.getOrElse(md.hash, CurrentVotes.empty)
          (WriteBlock(parseMichelsonScripts(Block(md, ops, votes))), accs)
      }
    }
  }

  private def toMichelsonScript[T <: MichelsonElement : Parser](json: String)(implicit tag: ClassTag[T]): String = {

    def unparsableResult(json: Any, exception: Option[Throwable] = None): String = {
      exception match {
        case Some(t) => logger.error(s"${tag.runtimeClass}: Error during conversion of $json", t)
        case None => logger.error(s"${tag.runtimeClass}: Error during conversion of $json")
      }

      s"Unparsable code: $json"
    }

    def parse(json: String): String = convert[T](json) match {
      case Right(convertedResult) => convertedResult
      case Left(exception) => unparsableResult(json, Some(exception))
    }

    Try(parse(json)).getOrElse(unparsableResult(json))
  }
}

/**
  * Adds more specific API functionalities to perform on a tezos node, in particular those involving write and cryptographic operations
  */
class TezosNodeSenderOperator(override val node: TezosRPCInterface, network: String, batchConf: BatchFetchConfiguration, sodiumConf: SodiumConfiguration)(implicit executionContext: ExecutionContext)
  extends TezosNodeOperator(node, network, batchConf)
  with LazyLogging {
  import com.muquit.libsodiumjna.{SodiumKeyPair, SodiumLibrary, SodiumUtils}
  import TezosNodeOperator._

  /** Type representing Map[String, Any] */
  type AnyMap = Map[String, Any]

  //used in subsequent operations using Sodium
  SodiumLibrary.setLibraryPath(sodiumConf.libraryPath)

  /**
    * Appends a key reveal operation to an operation group if needed.
    * @param operations The operations being forged as part of this operation group
    * @param managerKey The sending account's manager information
    * @param keyStore   Key pair along with public key hash
    * @return           Operation group enriched with a key reveal if necessary
    */
  def handleKeyRevealForOperations(
    operations: List[AnyMap],
    managerKey: ManagerKey,
    keyStore: KeyStore): List[AnyMap] =
    managerKey.key match {
      case Some(_) => operations
      case None =>
        val revealMap: AnyMap = Map(
          "kind"        -> "reveal",
          "public_key"  -> keyStore.publicKey
        )
        revealMap :: operations
    }

  /**
    * Forge an operation group using the Tezos RPC client.
    * @param blockHead  The block head
    * @param account    The sender's account
    * @param operations The operations being forged as part of this operation group
    * @param keyStore   Key pair along with public key hash
    * @param fee        Fee to be paid
    * @return           Forged operation bytes (as a hex string)
    */
  def forgeOperations(
    blockHead: Block,
    account: Account,
    operations: List[AnyMap],
    keyStore: KeyStore,
    fee: Option[Float]): Future[String] = {
    val payload: AnyMap = fee match {
      case Some(feeAmt) =>
        Map(
          "branch" -> blockHead.data.hash,
          "source" -> keyStore.publicKeyHash,
          "operations" -> operations,
          "counter" -> (account.counter + 1),
          "fee" -> feeAmt,
          "kind" -> "manager",
          "gas_limit" -> "120",
          "storage_limit" -> 0
        )
      case None =>
        Map(
          "branch" -> blockHead.data.header.predecessor,
          "operations" -> operations
        )
    }
    node.runAsyncPostQuery(network, "/blocks/head/proto/helpers/forge/operations", Some(JsonUtil.toJson(payload)))
      .map(json => fromJson[ForgedOperation](json).operation)
  }

  /**
    * Signs a forged operation
    * @param forgedOperation  Forged operation group returned by the Tezos client (as a hex string)
    * @param keyStore         Key pair along with public key hash
    * @return                 Bytes of the signed operation along with the actual signature
    */
  def signOperationGroup(forgedOperation: String, keyStore: KeyStore): Try[SignedOperationGroup] = Try{
    val privateKeyBytes = CryptoUtil.base58CheckDecode(keyStore.privateKey, "edsk").get
    val watermark = "03"  // In the future, we must support "0x02" for endorsements and "0x01" for block signing.
    val watermarkedForgedOperationBytes = SodiumUtils.hex2Binary(watermark + forgedOperation)
    val hashedWatermarkedOpBytes = SodiumLibrary.cryptoGenerichash(watermarkedForgedOperationBytes, 32)
    val opSignature: Array[Byte] = SodiumLibrary.cryptoSignDetached(hashedWatermarkedOpBytes, privateKeyBytes.toArray)
    val hexSignature: String = CryptoUtil.base58CheckEncode(opSignature.toList, "edsig").get
    val signedOpBytes = SodiumUtils.hex2Binary(forgedOperation) ++ opSignature
    SignedOperationGroup(signedOpBytes, hexSignature)
  }

  /**
    * Computes the ID of an operation group using Base58Check.
    * @param signedOpGroup  Signed operation group
    * @return               Base58Check hash of signed operation
    */
  def computeOperationHash(signedOpGroup: SignedOperationGroup): Try[String] =
    Try{
      SodiumLibrary.cryptoGenerichash(signedOpGroup.bytes, 32)
    }.flatMap { hash =>
      CryptoUtil.base58CheckEncode(hash.toList, "op")
    }

  /**
    * Applies an operation using the Tezos RPC client.
    * @param blockHead            Block head
    * @param operationGroupHash   Hash of the operation group being applied (in Base58Check format)
    * @param forgedOperationGroup Forged operation group returned by the Tezos client (as a hex string)
    * @param signedOpGroup        Signed operation group
    * @return                     Array of contract handles
    */
  def applyOperation(
    blockHead: Block,
    operationGroupHash: String,
    forgedOperationGroup: String,
    signedOpGroup: SignedOperationGroup): Future[AppliedOperation] = {
    val payload: AnyMap = Map(
      "pred_block" -> blockHead.data.header.predecessor,
      "operation_hash" -> operationGroupHash,
      "forged_operation" -> forgedOperationGroup,
      "signature" -> signedOpGroup.signature
    )
    node.runAsyncPostQuery(network, "/blocks/head/proto/helpers/apply_operation", Some(JsonUtil.toJson(payload)))
      .map { result =>
        logger.debug(s"Result of operation application: $result")
        JsonUtil.fromJson[AppliedOperation](result)
      }
  }

  /**
    * Injects an opertion using the Tezos RPC client.
    * @param signedOpGroup  Signed operation group
    * @return               ID of injected operation
    */
  def injectOperation(signedOpGroup: SignedOperationGroup): Future[String] = {
    val payload: AnyMap = Map(
      "signedOperationContents" -> signedOpGroup.bytes.map("%02X" format _).mkString
    )
    node.runAsyncPostQuery(network, "/inject_operation", Some(JsonUtil.toJson(payload)))
      .map(result => fromJson[InjectedOperation](result).injectedOperation)
  }

  /**
    * Master function for creating and sending all supported types of operations.
    * @param operations The operations to create and send
    * @param keyStore   Key pair along with public key hash
    * @param fee        The fee to use
    * @return           The ID of the created operation group
    */
  def sendOperation(operations: List[Map[String,Any]], keyStore: KeyStore, fee: Option[Float]): Future[OperationResult] = for {
    blockHead <- getBlockHead()
    accountId = AccountId(keyStore.publicKeyHash)
    account <- getAccountForBlock(blockHeadHash, accountId)
    accountManager <- getAccountManagerForBlock(blockHeadHash, accountId)
    operationsWithKeyReveal = handleKeyRevealForOperations(operations, accountManager, keyStore)
    forgedOperationGroup <- forgeOperations(blockHead, account, operationsWithKeyReveal, keyStore, fee)
    signedOpGroup <- Future.fromTry(signOperationGroup(forgedOperationGroup, keyStore))
    operationGroupHash <- Future.fromTry(computeOperationHash(signedOpGroup))
    appliedOp <- applyOperation(blockHead, operationGroupHash, forgedOperationGroup, signedOpGroup)
    operation <- injectOperation(signedOpGroup)
  } yield OperationResult(appliedOp, operation)

  /**
    * Creates and sends a transaction operation.
    * @param keyStore   Key pair along with public key hash
    * @param to         Destination public key hash
    * @param amount     Amount to send
    * @param fee        Fee to use
    * @return           The ID of the created operation group
    */
  def sendTransactionOperation(
    keyStore: KeyStore,
    to: String,
    amount: Float,
    fee: Float
  ): Future[OperationResult] = {
    val transactionMap: Map[String,Any] = Map(
      "kind"        -> "transaction",
      "amount"      -> amount,
      "destination" -> to,
      "parameters"  -> MichelsonExpression("Unit", List[String]())
    )
    val operations = transactionMap :: Nil
    sendOperation(operations, keyStore, Some(fee))
  }

  /**
    * Creates and sends a delegation operation.
    * @param keyStore Key pair along with public key hash
    * @param delegate Account ID to delegate to
    * @param fee      Operation fee
    * @return
    */
  def sendDelegationOperation(
    keyStore: KeyStore,
    delegate: String,
    fee: Float): Future[OperationResult] = {
    val transactionMap: Map[String,Any] = Map(
      "kind"        -> "delegation",
      "delegate"    -> delegate
    )
    val operations = transactionMap :: Nil
    sendOperation(operations, keyStore, Some(fee))
  }

  /**
    * Creates and sends an origination operation.
    * @param keyStore     Key pair along with public key hash
    * @param amount       Initial funding amount of new account
    * @param delegate     Account ID to delegate to, blank if none
    * @param spendable    Is account spendable?
    * @param delegatable  Is account delegatable?
    * @param fee          Operation fee
    * @return
    */
  def sendOriginationOperation(
    keyStore: KeyStore,
    amount: Float,
    delegate: String,
    spendable: Boolean,
    delegatable: Boolean,
    fee: Float): Future[OperationResult] = {
    val transactionMap: Map[String,Any] = Map(
      "kind"          -> "origination",
      "balance"       -> amount,
      "managerPubkey" -> keyStore.publicKeyHash,
      "spendable"     -> spendable,
      "delegatable"   -> delegatable,
      "delegate"      -> delegate
    )
    val operations = transactionMap :: Nil
    sendOperation(operations, keyStore, Some(fee))
  }

  /**
    * Creates a new Tezos identity.
    * @return A new key pair along with a public key hash
    */
  def createIdentity(): Try[KeyStore] = {

    //The Java bindings for libSodium don't support generating a key pair from a seed.
    //We will revisit this later in order to support mnemomics and passphrases
    //val mnemonic = bip39.generate(Entropy128, WordList.load(EnglishWordList).get, new SecureRandom())
    //val seed = bip39.toSeed(mnemonic, Some(passphrase))

    val keyPair: SodiumKeyPair = SodiumLibrary.cryptoSignKeyPair()
    val rawPublicKeyHash = SodiumLibrary.cryptoGenerichash(keyPair.getPublicKey, 20)
    for {
      privateKey <- CryptoUtil.base58CheckEncode(keyPair.getPrivateKey, "edsk")
      publicKey <- CryptoUtil.base58CheckEncode(keyPair.getPublicKey, "edpk")
      publicKeyHash <- CryptoUtil.base58CheckEncode(rawPublicKeyHash, "tz1")
    } yield KeyStore(privateKey = privateKey, publicKey = publicKey, publicKeyHash = publicKeyHash)
  }

}