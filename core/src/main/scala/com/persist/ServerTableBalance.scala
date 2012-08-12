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

import JsonOps._
import akka.actor.ActorSystem
import akka.actor.ActorRef
import Exceptions._

private[persist] trait ServerTableBalanceComponent { this: ServerTableAssembly =>
  val bal: ServerTableBalance
  class ServerTableBalance(system: ActorSystem) {
    var canSend = false
    var canReport = false
    var threshold: Long = 1 // should be at least 1
    var forceEmpty = false

    // prev node
    var prevNodeName = ""
    private var prevNode: ActorRef = null

    // next node
    var nextNodeName = ""
    private var nextNode: ActorRef = null
    var cntToNext: Long = 0
    var cntFromPrev: Long = 0

    var nextCount: Long = Long.MaxValue
    var nextLow: String = info.high

    var singleNode = true

    setPrevNextName()

    def setPrevNextName() = {
      val nextNodeName = info.config.rings(info.ringName).nextNodeName(info.nodeName)
      val prevNodeName = info.config.rings(info.ringName).prevNodeName(info.nodeName)
      if (nextNodeName != this.nextNodeName) nextNode = null
      if (prevNodeName != this.prevNodeName) prevNode = null
      this.prevNodeName = prevNodeName
      this.nextNodeName = nextNodeName
      singleNode = info.nodeName == nextNodeName
    }

    def setPrevNext() {
      if (nextNode == null) {
        nextNode = info.config.getRef(system, info.ringName, nextNodeName, info.tableName)
      }
      if (prevNode == null) {
        prevNode = info.config.getRef(system, info.ringName, prevNodeName, info.tableName)
      }
    }

    /*
    def resetPrevNextX() {
      if (setPrevNextName() || nextNode == null) {
        setPrevNext()
      }
      singleNode = info.nodeName == prevNodeName
      if (singleNode) {
        nextLow = info.high
        canSend = false
        canReport = false
      }
    }
    */

    def inNext(key: String) = {
      if (singleNode) {
        false
      } else {
        nextLow.startsWith(key)
      }
    }

    def sendPrefix(prefix: String, key: String, meta: String, v: String) {
      val request = JsonObject("p" -> prefix, "k" -> key, "m" -> meta, "v" -> v)
      nextNode ! ("fromPrev", Compact(request))
    }

    def isBusy: Boolean = {
      val count = info.storeTable.size()
      if (count > 1 && info.high != nextLow) {
        true
      } else if (forceEmpty && count > 0) {
        true
      } else {
        false
      }
    }

    def sendToNext {
      val count = info.storeTable.size()
      if (count == 0) return // nothing to send
      if (!forceEmpty) {
        if (count == 1) return // to ensure there is always at least 1 negative range in ring
        val incr = if (info.low > info.high) 1 else 0
        if (count < nextCount + incr + threshold) return // next is not enough smaller
      }
      if (info.high != nextLow) return // send already in progress
      val key = info.storeTable.prev(nextLow, false) match {
        case Some(key) => { key }
        case None => {
          info.storeTable.last match {
            case Some(key) => { key }
            case None => {
              throw InternalError("sendToNext 1")
            }
          }
        }
      }
      if (!back.canSendBalance(key)) return // waiting on use by background task
      val meta = info.storeTable.getMeta(key) match {
        case Some(value: String) => value
        case None => {
          throw InternalError("sendToNext 2")
        }
      }
      val v = info.storeTable.get(key) match {
        case Some(value: String) => value
        case None => {
          throw InternalError("sendToNext 3")
        }
      }
      info.high = key
      info.storeTable.put("!high", info.high)
      nextCount += 1
      cntToNext += 1
      val uid = info.uidGen.get
      var request = JsonObject("t" -> uid, "k" -> key, "m" -> meta, "v" -> v)
      if (forceEmpty && count == 1) {
        request += ("low" -> info.low)
        info.low = key
        info.high = key
        nextLow = key
        canReport = false
      }
      // TODO send prefixes if needed
      // if has prefixes
      // if prefixes of item sent not already on next node
      //info.nextNode ! ("fromPrev", uid, key, meta, v)
      nextNode ! ("fromPrev", Compact(request))
    }

    //def fromPrev(uid:Long, key: String, meta: String, value: String) {
    def fromPrev(r: String) {
      // TODO if has prefixes
      // if current prefix not present add the prefix
      // else if current prefix is older -- run map prefix on items except sent
      // else if prefix is newer - run map prefix on new item 
      // ack the prefixes somewhere???
      val request = Json(r)
      val t = jgetLong(request, "t")
      val prefix = jgetString(request, "p")
      val key = jgetString(request, "k")
      val meta = jgetString(request, "m")
      val value = jgetString(request, "v")
      val low = jgetString(request, "low")
      if (prefix != "") {
        mr.map(key, prefix, meta, value)
        val response = JsonObject("p" -> JsonObject("p" -> prefix, "k" -> key, "m" -> meta))
        prevNode ! ("fromNext", Compact(response))
        return
      }

      if (t != 0) info.uidGen.set(t) // sync current clock to prev clock
      cntFromPrev += 1
      info.storeTable.putBoth(key, meta, value)
      info.low = if (low == "") key else low
      if (low != "" && prevNodeName == nextNodeName) {
        info.low = ""
        info.high = "\uFFFF"
        nextLow = info.high
      }
      info.storeTable.put("!low", info.low)
      if (mr.hasReduce) mr.reduceOut(key, info.absentMetaS, "null", meta, value)
    }

    private var prevReportedCount: Long = 0
    private var prevReportedLow = info.low

    def reportToPrev {
      val count = info.storeTable.size()
      if ((canSend && prevReportedCount != count) || prevReportedLow != info.low) {
        val response = JsonObject("n" -> count, "k" -> info.low)
        prevNode ! ("fromNext", Compact(response))
        prevReportedCount = count
        prevReportedLow = info.low
      }
    }

    private def remove(k: String) {
      if (mr.hasReduce) {
        val oldMeta = info.storeTable.getMeta(k) match {
          case Some(s: String) => s
          case None => ""
        }
        val oldValue = info.storeTable.get(k) match {
          case Some(s: String) => s
          case None => ""
        }
        mr.reduceOut(k, oldMeta, oldValue, info.absentMetaS, "null")
      }
      info.storeTable.remove(k)
    }

    def fromNext(res: String) {
      val response = Json(res)
      val prefix = jgetString(response, "p", "p")
      if (prefix != "") {
        val key = jgetString(response, "p", "k")
        val meta = jgetString(response, "p", "m")
        mr.ackPrefix(prefix, key, meta)
        return
      }
      val ncount = jgetLong(response, "n")
      var nlow = jgetString(response, "k")
      if (forceEmpty && info.low == info.high) {
        remove(info.low)
      } else if (nextLow != nlow) {
        for (k <- info.range(nlow, nextLow, singleNode)) {
          remove(k)
        }
        nextLow = nlow
      }
      nextCount = ncount
    }
  }
}