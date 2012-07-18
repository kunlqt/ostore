/*
 *  Copyright 2012 Persist Software
 *  
 *   http://www.persist.com
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
*/

package com.persist

import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ActorRef
import JsonOps._
import akka.pattern._
import akka.util.Timeout
import akka.util.duration._
import akka.dispatch.Await
import scala.collection.immutable.TreeMap

private[persist] class RingInfo(val name: String, val ring: ActorRef)

private[persist] class ServerDatabase(var config: DatabaseConfig, serverConfig: Json, create: Boolean) extends CheckedActor {
  private val system = context.system
  val serverName = jgetString(serverConfig, "host") + ":" + jgetInt(serverConfig, "port")
  val databaseName = config.name
  val send = context.actorOf(Props(new Send(context.system, config)), name = "@send")
  var rings = TreeMap[String, RingInfo]()
  implicit val timeout = Timeout(5 seconds)

  def newRing(ringName: String) {
    val ring = context.actorOf(Props(new ServerRing(databaseName, ringName, send, config, serverConfig, create)), name = ringName)
    val f = ring ? ("start1")
    Await.result(f, 5 seconds)
    val info = new RingInfo(ringName, ring)
    rings += (ringName -> info)
  }

  // TODO do in parallel
  for ((ringName, ringConfig) <- config.rings) {
    var hasRing = false
    for ((nodeName, nodeConfig) <- ringConfig.nodes) {
      if (nodeConfig.server.name == serverName) {
        hasRing = true
      }
    }
    if (hasRing) newRing(ringName)
  }

  def rec = {
    case ("start1") => {
      sender ! Codes.Ok
    }
    case ("start2") => {
      for ((ringName, ringInfo) <- rings) {
        val f = ringInfo.ring ? ("start2")
        val v = Await.result(f, 5 seconds)
      }
      sender ! Codes.Ok
    }
    case ("stop1") => {
      for ((ringName, ringInfo) <- rings) {
        val f = ringInfo.ring ? ("stop1")
        val v = Await.result(f, 5 seconds)
      }
      sender ! Codes.Ok
    }
    case ("stop2") => {
      for ((ringName, ringInfo) <- rings) {
        val f = ringInfo.ring ? ("stop2")
        val v = Await.result(f, 5 seconds)
      }
      val f = send ? ("stop")
      Await.result(f, 5 seconds)
      sender ! Codes.Ok
    }
    case ("stopBalance", ringName: String, nodeName: String) => {
      for ((ringName1, ringInfo) <- rings) {
        val nodeName1 = if (ringName == ringName1) nodeName else ""
        val f = ringInfo.ring ? ("stopBalance", nodeName1)
        val v = Await.result(f, 5 seconds)
      }
      sender ! Codes.Ok
    }
    case ("startBalance") => {
      for ((ringName, ringInfo) <- rings) {
        val f = ringInfo.ring ? ("startBalance")
        val v = Await.result(f, 5 seconds)
      }
      sender ! Codes.Ok
    }
    case ("busyBalance") => {
      var code = Codes.Ok
      for ((ringName, ringInfo) <- rings) {
        val f = ringInfo.ring ? ("busyBalance")
        val code1 = Await.result(f, 5 seconds)
        if (code1 == Codes.Busy) code = Codes.Busy
      }
      sender ! (code, "")
    }
    case ("addNode", ringName: String, nodeName: String, config: DatabaseConfig) => {
      for ((ringName1, ringInfo) <- rings) {
        val nodeName1 = if (ringName == ringName1) nodeName else ""
        val f = ringInfo.ring ? ("addNode", nodeName1, config)
        val v = Await.result(f, 5 seconds)
      }
      this.config = config
      sender ! Codes.Ok
    }
    case ("deleteNode", ringName: String, nodeName: String, config: DatabaseConfig) => {
      for ((ringName1, ringInfo) <- rings) {
        val nodeName1 = if (ringName == ringName1) nodeName else ""
        val f = ringInfo.ring ? ("deleteNode", nodeName1, config)
        val (code: String, empty: Boolean) = Await.result(f, 5 seconds)
        if (empty) {
          val stopped = gracefulStop(ringInfo.ring, 5 seconds)(system)
          Await.result(stopped, 5 seconds)
          rings -= ringName
        }
      }
      this.config = config
      sender ! (Codes.Ok, rings.size == 0)
    }
    case ("getLowHigh", ringName: String, nodeName: String, tableName: String) => {
      val ringInfo = rings(ringName)
      val f = ringInfo.ring ? ("getLowHigh", nodeName, tableName)
      val (code: String, result: Json) = Await.result(f, 5 seconds)
      sender ! (Codes.Ok, result)
    }
    case ("setLowHigh", ringName: String, nodeName: String, tableName: String, low: String, high: String) => {
      val ringInfo = rings(ringName)
      val f = ringInfo.ring ? ("setLowHigh", nodeName, tableName, low, high)
      Await.result(f, 5 seconds)
      sender ! Codes.Ok
    }
    case ("addTable1", tableName: String, config: DatabaseConfig) => {
      for ((ringName, ringInfo) <- rings) {
        val f = ringInfo.ring ? ("addTable1", tableName, config)
        val v = Await.result(f, 5 seconds)
      }
      sender ! Codes.Ok
    }
    case ("addTable2", tableName: String) => {
      for ((ringName, ringInfo) <- rings) {
        val f = ringInfo.ring ? ("addTable2", tableName)
        val v = Await.result(f, 5 seconds)
      }
      sender ! Codes.Ok
    }
    case ("deleteTable1", tableName: String, config: DatabaseConfig) => {
      for ((ringName, ringInfo) <- rings) {
        val f = ringInfo.ring ? ("deleteTable1", tableName, config)
        val v = Await.result(f, 5 seconds)
      }
      this.config = config
      sender ! Codes.Ok
    }
    case ("deleteTable2", tableName: String) => {
      for ((ringName, ringInfo) <- rings) {
        val f = ringInfo.ring ? ("deleteTable2", tableName)
        val v = Await.result(f, 5 seconds)
      }
      sender ! Codes.Ok
    }
  }

}