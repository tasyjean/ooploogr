package org.ooploogr

import reactivemongo.bson._
import reactivemongo.bson.handlers.DefaultBSONHandlers._
import org.jboss.netty.buffer.ChannelBuffers
import java.nio.ByteOrder
import org.bson.{BasicBSONObject, BSON}
import java.lang.String
import java.util.{Date, StringTokenizer}
import reactivemongo.api.{QueryOpts, MongoConnection, DefaultDB}
import play.api.libs.iteratee.Iteratee
import concurrent.{Await, Future}
import reactivemongo.bson.BSONTimestamp
import util.Failure
import reactivemongo.core.commands.RawCommand
import util.Success
import reactivemongo.bson.BSONString
import scala.concurrent.duration._
import java.text.DecimalFormat

/**
 * @author gstathis
 *         Created on: 2/18/13
 */
object Ooploogr extends App {
  var SOURCE_HOST: String = null
  var DESTINATION_HOST: String = null
  var FROM_TIME: String = null
  var COLLECTION_STRING: String = null
  var COL_REMAPPINGS: String = null
  var DB_REMAPPINGS: String = null
  var TARGET_DBS: Map[String, DefaultDB] = Map[String, DefaultDB]()
  var NAMESPACE_COLLECTION_MAP: Map[String, String] = Map[String, String]()
  val TIMEOUT = Duration(30000, MILLISECONDS)
  var OPERATIONS_READ: Int = 0
  var OPERATIONS_SKIPPED: Int = 0
  var INSERT_COUNT: Int = 0
  var UPDATE_COUNT: Int = 0
  var DELETE_COUNT: Int = 0
  val REPORT_INTERVAL = 10000L
  val LONG_FORMAT = new DecimalFormat("###,###")

  Console.println("Starting Ooploogr")
  val START_TIME = System.currentTimeMillis()
  if (!parseArgs(args)) {
    usage()
    sys.exit()
  }

  var timestamp = parseTimestamp(FROM_TIME)
  var collections: (List[String], List[String]) = parseCollections(COLLECTION_STRING)
  var mappings: (Map[String, String], Map[String, String]) = parseMappings(DB_REMAPPINGS, COL_REMAPPINGS)

  import scala.concurrent.ExecutionContext.Implicits.global

  val SOURCE_CONNECTION = MongoConnection(List(SOURCE_HOST))
  val DESTINATION_CONNECTION = MongoConnection(List(DESTINATION_HOST))
  val db = SOURCE_CONNECTION("local")
  val collection = db("oplog.rs")
  var query = BSONDocument()
  if (timestamp != 0)
    query = BSONDocument("ts" -> BSONDocument("$gt" -> toBSONTimestamp(timestamp)))
  val cursor = collection.find(query, QueryOpts().tailable.awaitData)
  var lastOutput = System.currentTimeMillis()
  cursor.enumerate.apply(Iteratee.foreach {
    doc =>
      if (shouldProcess(doc, collections._1, collections._2)) {
        processRecord(doc)
        Ooploogr.synchronized {
          OPERATIONS_READ = OPERATIONS_READ + 1
        }
      }
      else
        Ooploogr.synchronized {
          OPERATIONS_SKIPPED = OPERATIONS_SKIPPED + 1
        }

      Ooploogr.synchronized {
        val durationSinceLastOutput = System.currentTimeMillis() - lastOutput;
        if (durationSinceLastOutput > REPORT_INTERVAL) {
          report(INSERT_COUNT,
            UPDATE_COUNT,
            DELETE_COUNT,
            OPERATIONS_READ,
            OPERATIONS_SKIPPED,
            System.currentTimeMillis() - START_TIME,
            fromBSONTimestamp(doc.get("ts").get.asInstanceOf[BSONTimestamp]));
          lastOutput = System.currentTimeMillis();
        }
      }
  })

  // Look for a stop file to quit
  while (true) {
    val file = new java.io.File("stop.txt")
    if(file.exists()) {
      Console.println("Found stop file, exiting")
      closeConnections()
      file.delete()
      System.exit(0)
    }
    else
      try {
        Thread.sleep(1000L)
      }
      catch {
        case e: Exception => {
          Console.println("Application thread was interrupted")
          System.exit(closeConnections())
        }
      }
  }

  /*
   * FUNCTIONS
   */

  def toBSONTimestamp(t: Int): BSONTimestamp = {
    val buffer = ChannelBuffers.dynamicBuffer(ByteOrder.LITTLE_ENDIAN, 256)
    buffer.writeBytes(BSON.encode(new BasicBSONObject("ts", new org.bson.types.BSONTimestamp(t, 0))))
    BSONDocument(buffer).get("ts").get.asInstanceOf[BSONTimestamp]
  }

  def fromBSONTimestamp(t: BSONTimestamp): Int = {
    val reverseDoc = BSONDocument("ts" -> t)
    val backTobson = BSON.decode(reverseDoc.toBuffer.array())
    backTobson.get("ts").asInstanceOf[org.bson.types.BSONTimestamp].getTime
  }

  def printFlat(doc: BSONDocument): String = {
    "{ "+printFlat(doc.toTraversable.iterator)+" }"
  }

  private def printFlat(it: Iterator[BSONElement]): String = {
    (for(v <- it) yield {
      v.value match {
        case doc :TraversableBSONDocument => v.name + ": { " + printFlat(doc.iterator) + " }"
        case array :TraversableBSONArray => v.name + ": [ " + printFlat(array.iterator) +" ]"
        case _ => v.name + ": " + v.value.toString
      }
    }).mkString(", ")
  }

  private def closeConnections(): Int = {
    Console.println("Closing connections")
    val sourceClose = SOURCE_CONNECTION.askClose()(TIMEOUT)
    waitForClose(sourceClose, "source")
    val destinationClose = DESTINATION_CONNECTION.askClose()(TIMEOUT)
    waitForClose(destinationClose, "destination")
    return 0
  }

  private def waitForClose(closeFuture: Future[_], name: String) = {
    closeFuture.onComplete {
      case Failure(e) =>
        Console.println("Could not close " + name + " connection: " + e.getMessage)
      case Success(lasterror) => {
        Console.println("Closed " + name + " connection")
      }
    }
    Await.ready(closeFuture, TIMEOUT)
  }

  private def report(inserts: Long, updates: Long, deletes: Long, totalCount: Long, skips: Long, duration: Long, timestamp: Int) {
    val brate = totalCount.asInstanceOf[Double] / ((duration) / 1000.0)
    Console.println("inserts: "
      + LONG_FORMAT.format(inserts) + ", updates: " + LONG_FORMAT.format(updates)
      + ", deletes: " + LONG_FORMAT.format(deletes) + ", skips: " + LONG_FORMAT.format(skips)
      + " (" + LONG_FORMAT.format(brate) + " req/sec), last ts: " + new Date(timestamp * 1000L));
  }

  private def processRecord(doc: TraversableBSONDocument) = {
    // Process record
    val operationType: String = doc.get("op").get.asInstanceOf[BSONString].value
    val namespace: String = doc.get("ns").get.asInstanceOf[BSONString].value
    val targetCollectionName: String = getCollectionFromNamespace(namespace)
    val operation: BSONDocument = BSONDocument(doc.get("o").get.asInstanceOf[BSONDocument].toBuffer)
    val targetDb = getDB(namespace, mappings._1)
    val targetCollection = targetDb.collection(targetCollectionName)

    try {
      if ("i".equals(operationType)) {
        val futureInsert = targetCollection.insert(operation)
        completeOrError(futureInsert, String.format("{ ns: %s, op:%s, o: %s }", namespace, operationType, printFlat(operation)), {
          Ooploogr.synchronized {
            INSERT_COUNT = INSERT_COUNT + 1
          }
        })
        Await.ready(futureInsert, TIMEOUT)
      }
      else if ("d".equals(operationType)) {
        val futureRemove = targetCollection.remove(operation)
        completeOrError(futureRemove, String.format("{ ns: %s, op:%s, o: %s }", namespace, operationType, printFlat(operation)), {
          Ooploogr.synchronized {
            DELETE_COUNT = DELETE_COUNT + 1
          }
        })
        Await.ready(futureRemove, TIMEOUT)
      }
      else if ("u".equals(operationType)) {
        val o2: BSONDocument = BSONDocument(doc.get("o2").get.asInstanceOf[BSONDocument].toBuffer)
        val futureUpdate = targetCollection.update(o2, operation)
        completeOrError(futureUpdate, String.format("{ ns: %s, op:%s, o2: %s , o: %s }", namespace, operationType, printFlat(o2), printFlat(operation)), {
          Ooploogr.synchronized {
            UPDATE_COUNT = UPDATE_COUNT + 1
          }
        })
        Await.ready(futureUpdate, TIMEOUT)
      }
      else if ("c".equals(operationType)) {
        val futureCommand = db.command(RawCommand(operation))
        completeOrError(futureCommand, String.format("{ ns: %s, op:%s, o: %s }", namespace, operationType, printFlat(operation)), {})
        Await.ready(futureCommand, TIMEOUT)
      }
    } catch {
      case
        e: Exception =>
        Console.println("failed to process record " + printFlat(operation))
        e.printStackTrace()
    }
  }

  private def completeOrError(future: Future[_], msg: String, increment: => Unit) = {
    future.onComplete {
      case Failure(e) =>
        Console.println(String.format("Did not process '%s'. Error was '%s'",msg, e.getMessage))
      case Success(lasterror) => {
        increment
        //Console.println(msg)
      }
    }
  }

  private def getDB(namespace: String, mappings: Map[String, String]): DefaultDB = {
    val dbName = getDatabaseMapping(namespace, mappings)
    val conn: Option[DefaultDB] = TARGET_DBS.get(dbName)
    if (conn.isEmpty) {
      val db = DESTINATION_CONNECTION(dbName)
      TARGET_DBS += (dbName -> db)
      return db
    }
    else
      return conn.get
  }

  private def parseTimestamp(fromTime: String): Int = {
    var ret = 0
    if (null != fromTime) {
      try {
        ret = java.lang.Integer.parseInt(fromTime)
      }
      catch {
        case e: Exception =>
          Console.println("Timestamp provided is not a valid number: " + fromTime)
      }
    }
    ret
  }

  private def parseCollections(collectionString: String): (List[String], List[String]) = {
    var collectionsToAdd: List[String] = List()
    var collectionsToSkip: List[String] = List()
    if (collectionString != null) {
      var hasIncludes = false
      val collectionNames: Array[String] = collectionString.split(",")
      collectionNames.foreach {
        collectionName =>
          if (collectionName.startsWith("!")) {
            //	skip it
            collectionsToSkip = collectionsToSkip.::(collectionName.substring(1).trim())
          }
          else {
            collectionsToAdd = collectionsToAdd.::(collectionName.trim())
            hasIncludes = true
          }
      }
      if (!hasIncludes) {
        collectionsToAdd.::("*")
      }
    }
    else {
      collectionsToAdd.::("*")
    }
    (collectionsToAdd, collectionsToSkip)
  }

  private def parseMappings(databaseMappingString: String, collectionMappingString: String): (Map[String, String], Map[String, String]) = {
    var databaseMappings: Map[String, String] = Map[String, String]()
    var collectionMappings: Map[String, String] = Map[String, String]()

    if (null != databaseMappingString) {
      val tk = new StringTokenizer(databaseMappingString, ",")
      while (tk.hasMoreElements) {
        val split = tk.nextElement().asInstanceOf[String].split("=")
        databaseMappings += split(0) -> split(1)
      }
    }

    if (null != collectionMappingString) {
      val tk = new StringTokenizer(collectionMappingString, ",")
      while (tk.hasMoreElements) {
        val split = tk.nextElement().asInstanceOf[String].split("=")
        collectionMappings += split(0) -> split(1)
      }
    }
    (databaseMappings, collectionMappings)
  }

  private def getCollectionFromNamespace(namespace: String): String = {
    if (NAMESPACE_COLLECTION_MAP.contains(namespace)) {
      return NAMESPACE_COLLECTION_MAP.get(namespace).get
    }
    val parts: Array[String] = namespace.split("\\.")
    if (parts == null || parts.length == 1) {
      return null
    }
    var collection: String = null
    if (parts.length == 2) {
      collection = parts(1)
    }
    else {
      collection = namespace.substring(0, parts(0).length + 1)
    }
    NAMESPACE_COLLECTION_MAP += (namespace -> collection)
    collection
  }

  private def getDatabaseMapping(namespace: String, mappings: Map[String, String]): String = {
    val parts: Array[String] = namespace.split("\\.")
    if (parts == null || parts.length == 1) {
      return null
    }
    val databaseName: String = parts(0)
    mappings.getOrElse(databaseName, databaseName)
  }

  private def shouldProcess(doc: BSONDocument, includedCollections: List[String], excludedCollections: List[String]): Boolean = {
    val namespace = doc.toTraversable.get("ns").get.asInstanceOf[BSONString].value
    if (null == namespace || "".equals(namespace))
      return false
    if (excludedCollections.size == 0 && includedCollections.size == 0)
      return true
    if (excludedCollections.contains(namespace))
      return false
    if (includedCollections.contains(namespace) || includedCollections.contains("*"))
      return true
    if (namespace.indexOf('.') > 0 && includedCollections.contains(namespace.substring(0, namespace.indexOf('.'))))
      return true

    return false
  }

  private def parseArgs(args: Array[String]): Boolean = {
    var skip: Boolean = false
    for (i <- 0 to args.length - 1) {
      if (!skip) {
        args(i) match {
          case "-s" => SOURCE_HOST = args(i + 1); skip = true
          case "-d" => DESTINATION_HOST = args(i + 1); skip = true
          case "-t" => FROM_TIME = args(i + 1); skip = true
          case "-c" => COLLECTION_STRING = args(i + 1); skip = true
          case "-r" => COL_REMAPPINGS = args(i + 1); skip = true
          case "-R" => DB_REMAPPINGS = args(i + 1); skip = true
          case _ => Console.println("Unknown parameter " + args(i))
          return false
        }
      }
      else
        skip = false
    }
    if (null == SOURCE_HOST)
      SOURCE_HOST = "localhost"
    if (null == DESTINATION_HOST)
      DESTINATION_HOST = "localhost"
    if (SOURCE_HOST.equals(DESTINATION_HOST) && null == DB_REMAPPINGS) {
      Console.println("Source and destination hosts need to be different if no DB remappings are used. Currently set to " + SOURCE_HOST)
      return false
    }
    true
  }

  private def usage() {
    Console.println("usage: Ooploogr")
    Console.println(" -s : source database host[:port]")
    Console.println(" -d : destination database host[:port]")
    Console.println(" [-t : oplog timestamp from which to start playback]")
    Console.println(" [-c : CSV of collections to process, scoped to the db (database.collection), ! will exclude]")
    Console.println(" [-r : collection re-targeting (format: {SOURCE}={TARGET})]")
    Console.println(" [-R : database re-targeting (format: {SOURCE}={TARGET})]")
  }
}
