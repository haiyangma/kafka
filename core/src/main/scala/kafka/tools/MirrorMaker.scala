/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.tools

import kafka.utils.{Utils, CommandLineUtils, Logging}
import kafka.consumer._
import kafka.serializer._
import kafka.producer.{OldProducer, NewShinyProducer, BaseProducer}
import org.apache.kafka.clients.producer.ProducerRecord

import scala.collection.mutable.ListBuffer
import scala.collection.JavaConversions._

import java.util.concurrent.{BlockingQueue, ArrayBlockingQueue, CountDownLatch}

import joptsimple.OptionParser

object MirrorMaker extends Logging {

  private var connectors: Seq[ZookeeperConsumerConnector] = null
  private var consumerThreads: Seq[ConsumerThread] = null
  private var producerThreads: ListBuffer[ProducerThread] = null

  def main(args: Array[String]) {
    
    info ("Starting mirror maker")
    val parser = new OptionParser

    val consumerConfigOpt = parser.accepts("consumer.config",
      "Consumer config to consume from a source cluster. " +
      "You may specify multiple of these.")
      .withRequiredArg()
      .describedAs("config file")
      .ofType(classOf[String])

    val producerConfigOpt = parser.accepts("producer.config",
      "Embedded producer config.")
      .withRequiredArg()
      .describedAs("config file")
      .ofType(classOf[String])

    val useNewProducerOpt = parser.accepts("new.producer",
      "Use the new producer implementation.")

    val numProducersOpt = parser.accepts("num.producers",
      "Number of producer instances")
      .withRequiredArg()
      .describedAs("Number of producers")
      .ofType(classOf[java.lang.Integer])
      .defaultsTo(1)
    
    val numStreamsOpt = parser.accepts("num.streams",
      "Number of consumption streams.")
      .withRequiredArg()
      .describedAs("Number of threads")
      .ofType(classOf[java.lang.Integer])
      .defaultsTo(1)

    val bufferSizeOpt =  parser.accepts("queue.size", "Number of messages that are buffered between the consumer and producer")
      .withRequiredArg()
      .describedAs("Queue size in terms of number of messages")
      .ofType(classOf[java.lang.Integer])
      .defaultsTo(10000)

    val whitelistOpt = parser.accepts("whitelist",
      "Whitelist of topics to mirror.")
      .withRequiredArg()
      .describedAs("Java regex (String)")
      .ofType(classOf[String])

    val blacklistOpt = parser.accepts("blacklist",
            "Blacklist of topics to mirror.")
            .withRequiredArg()
            .describedAs("Java regex (String)")
            .ofType(classOf[String])

    val helpOpt = parser.accepts("help", "Print this message.")

    val options = parser.parse(args : _*)

    if (options.has(helpOpt)) {
      parser.printHelpOn(System.out)
      System.exit(0)
    }

    CommandLineUtils.checkRequiredArgs(parser, options, consumerConfigOpt, producerConfigOpt)
    if (List(whitelistOpt, blacklistOpt).count(options.has) != 1) {
      println("Exactly one of whitelist or blacklist is required.")
      System.exit(1)
    }

    val numProducers = options.valueOf(numProducersOpt).intValue()
    val numStreams = options.valueOf(numStreamsOpt).intValue()
    val bufferSize = options.valueOf(bufferSizeOpt).intValue()

    val useNewProducer = options.has(useNewProducerOpt)
    val producerProps = Utils.loadProps(options.valueOf(producerConfigOpt))

    // create data channel
    val mirrorDataChannel = new ArrayBlockingQueue[ProducerRecord](bufferSize)

    // create producer threads
    val producers = (1 to numProducers).map(_ => {
        if (useNewProducer)
          new NewShinyProducer(producerProps)
        else
          new OldProducer(producerProps)
      })

    producerThreads = new ListBuffer[ProducerThread]()
    var producerIndex: Int = 1
    for(producer <- producers) {
      val producerThread = new ProducerThread(mirrorDataChannel, producer, producerIndex)
      producerThreads += producerThread
      producerIndex += 1
    }

    // create consumer streams
    connectors = options.valuesOf(consumerConfigOpt).toList
            .map(cfg => new ConsumerConfig(Utils.loadProps(cfg)))
            .map(new ZookeeperConsumerConnector(_))

    val filterSpec = if (options.has(whitelistOpt))
      new Whitelist(options.valueOf(whitelistOpt))
    else
      new Blacklist(options.valueOf(blacklistOpt))

    var streams: Seq[KafkaStream[Array[Byte], Array[Byte]]] = Nil
    try {
      streams = connectors.map(_.createMessageStreamsByFilter(filterSpec, numStreams, new DefaultDecoder(), new DefaultDecoder())).flatten
    } catch {
      case t: Throwable =>
        fatal("Unable to create stream - shutting down mirror maker.")
        connectors.foreach(_.shutdown)
    }
    consumerThreads = streams.zipWithIndex.map(streamAndIndex => new ConsumerThread(streamAndIndex._1, mirrorDataChannel, producers, streamAndIndex._2))

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() {
        cleanShutdown()
      }
    })

    consumerThreads.foreach(_.start)
    producerThreads.foreach(_.start)

    // in case the consumer threads hit a timeout/other exception
    consumerThreads.foreach(_.awaitShutdown)
    cleanShutdown()
  }

  def cleanShutdown() {
    if (connectors != null) connectors.foreach(_.shutdown)
    if (consumerThreads != null) consumerThreads.foreach(_.awaitShutdown)
    if (producerThreads != null) {
      producerThreads.foreach(_.shutdown)
      producerThreads.foreach(_.awaitShutdown)
    }
    info("Kafka mirror maker shutdown successfully")
  }

  class ConsumerThread(stream: KafkaStream[Array[Byte], Array[Byte]],
                          mirrorDataChannel: BlockingQueue[ProducerRecord],
                          producers: Seq[BaseProducer],
                          threadId: Int)
          extends Thread with Logging {

    private val shutdownLatch = new CountDownLatch(1)
    private val threadName = "mirrormaker-consumer-" + threadId
    this.logIdent = "[%s] ".format(threadName)

    this.setName(threadName)

    override def run() {
      info("Starting mirror maker thread " + threadName)
      try {
        for (msgAndMetadata <- stream) {
          // If the key of the message is empty, put it into the universal channel
          // Otherwise use a pre-assigned producer to send the message
          if (msgAndMetadata.key == null) {
            trace("Send the non-keyed message the producer channel.")
            val data = new ProducerRecord(msgAndMetadata.topic, msgAndMetadata.message)
            mirrorDataChannel.put(data)
          } else {
            val producerId = Utils.abs(java.util.Arrays.hashCode(msgAndMetadata.key)) % producers.size()
            trace("Send message with key %s to producer %d.".format(java.util.Arrays.toString(msgAndMetadata.key), producerId))
            val producer = producers(producerId)
            producer.send(msgAndMetadata.topic, msgAndMetadata.key, msgAndMetadata.message)
          }
        }
      } catch {
        case e: Throwable =>
          fatal("Stream unexpectedly exited.", e)
      } finally {
        shutdownLatch.countDown()
        info("Stopped thread.")
      }
    }

    def awaitShutdown() {
      try {
        shutdownLatch.await()
      } catch {
        case e: InterruptedException => fatal("Shutdown of thread %s interrupted. This might leak data!".format(threadName))
      }
    }
  }

  class ProducerThread (val dataChannel: BlockingQueue[ProducerRecord],
                        val producer: BaseProducer,
                        val threadId: Int) extends Thread {
    val threadName = "mirrormaker-producer-" + threadId
    val logger = org.apache.log4j.Logger.getLogger(classOf[KafkaMigrationTool.ProducerThread].getName)
    val shutdownComplete: CountDownLatch = new CountDownLatch(1)

    private final val shutdownMessage : ProducerRecord = new ProducerRecord("shutdown", "shutdown".getBytes)

    setName(threadName)

    override def run {
      try {
        while (true) {
          val data: ProducerRecord = dataChannel.take
          logger.trace("Sending message with value size %d".format(data.value().size))

          if(data eq shutdownMessage) {
            logger.info("Producer thread " + threadName + " finished running")
            return
          }
          producer.send(data.topic(), data.key(), data.value())
        }
      } catch {
        case t: Throwable => {
          logger.fatal("Producer thread failure due to ", t)
        }
      } finally {
        shutdownComplete.countDown
      }
    }

    def shutdown {
      try {
        logger.info("Producer thread " + threadName + " shutting down")
        dataChannel.put(shutdownMessage)
      }
      catch {
        case ie: InterruptedException => {
          logger.warn("Interrupt during shutdown of ProducerThread", ie)
        }
      }
    }

    def awaitShutdown {
      try {
        shutdownComplete.await
        producer.close
        logger.info("Producer thread " + threadName + " shutdown complete")
      } catch {
        case ie: InterruptedException => {
          logger.warn("Interrupt during shutdown of ProducerThread")
        }
      }
    }
  }
}

