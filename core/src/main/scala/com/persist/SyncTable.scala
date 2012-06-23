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

import akka.actor.Actor
import akka.dispatch.Future
import Actor._
import JsonOps._
import akka.actor.ActorSystem
import akka.actor.Props
import akka.dispatch.DefaultPromise
import akka.util.duration._
import akka.dispatch._
import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout
import scala.collection.Traversable

/**
 * Instances of this class are returned by the [[com.persist.SyncTable]] all method.
 */
trait AllItems {
  def foreach(body: Json => Unit)
}


private object SyncAllItems {
  def apply(client: AsyncTable, options: JsonObject) = new SyncAllItems(client, options)
}

/**
 * Instances of this class are returned by the [[com.persist.SyncTable]] all method.
 */
private class SyncAllItems private[persist] (asyncClient: AsyncTable, options: JsonObject) extends AllItems {
  // TODO fix so only 1 wait for entire loop
  def foreach(body: Json => Unit) = {
    var f = asyncClient.all(options)
    var done = false
    while (!done) {
      var r = Await.result(f, 20 seconds)
      r match {
        case Some(item) => {
          body(item.value)
          f = item.next()
        }
        case None => done = true
      }
    }
  }
}

/**
 * This is the synchronous interface to OStore tables.
 * Instances of this class are created by the [[com.persist.Database]] syncTable method.
 */
class SyncTable private[persist] (tableName: String, system: ActorSystem, config:DatabaseConfig, send: ActorRef) {
  // TODO pass in config rather than default to first ring
  // TODO option to get config from remote server

  private implicit val executor = system.dispatcher

  private val asyncClient = new AsyncTable(tableName, system, send)

  /**
   * Modifies the value of an item. If the item does not exist it is created.
   *
   * @param key the key.
   * @param value the new value.
   * @param options optional json object containing options.
   *  - '''"w"=n''' write at least n rings before returning. Default is 1.
   *  - '''"fast"=true''' if true, returns when the item has been updated in server memory.
   *        If false, returns only after item has also been written to server disk. Default is false.
   *  - '''"ring"="ringName"''' write to this ring.
   *  - '''"expires"=t''' the millisecond time at which this item expires.
   */
  def put(key: JsonKey, value: Json, options: JsonObject = emptyJsonObject): Unit = {
    val f1 = asyncClient.put(key, value, options)
    Await.result(f1, 5 seconds)
  }

  /**
   * Provides optomistic concurrency control.
   * Modifies the value of an item only if its vector clock on the server matches
   * the vector clock passed in.
   *
   * @param key the key.
   * @param value the new value.
   * @param vectorClock the vector clock to match. This would typically have been returned by a previous get
   * call.
   * @param options optional json object containing options.
   *  - '''"w"=n''' write at least n rings before returning. Default is 1.
   *  - '''"fast"=true''' if true, returns when the item has been updated in server memory.
   *        If false, returns only after item has also been written to server disk. Default is false.
   *  - '''"ring"="ringName"''' write to this ring.
   *  - '''"expires"=t''' the millisecond time at which this item expires.
   *
   *  @return true if the item was modified (vector clock matched). False if item was not modified (vector
   *  clock did not match).
   */
  def conditionalPut(key: JsonKey, value: Json, vectorClock: Json, options: JsonObject = emptyJsonObject): Boolean = {
    val f1 = asyncClient.conditionalPut(key, value, vectorClock, options)
    Await.result(f1, 5 seconds)
  }

  /**
   * Creates an item only if it does not already exist.
   *
   * @param key the key.
   * @param value the new value.
   * @param options optional json object containing options.
   *  - '''"w"=n''' write at least n rings before returning. Default is 1.
   *  - '''"fast"=true''' if true, returns when the item has been updated in server memory.
   *        If false, returns only after item has also been written to server disk. Default is false.
   *  - '''"ring"="ringName"''' write to this ring.
   *  - '''"expires"=t''' the millisecond time at which this item expires.
   *
   *  @return true if the item was created. False if the item was not modified because it already exists.
   */
  def create(key: JsonKey, value: Json, options: JsonObject = emptyJsonObject): Boolean = {
    val f1 = asyncClient.create(key, value, options)
    Await.result(f1, 5 seconds)
  }

  /**
   * Deletes an item. Has no effect if the item does not exist
   *
   * @param key the key.
   * @param options optional json object containing options.
   *  - '''"w"=n''' write at least n rings before returning. Default is 1.
   *  - '''fast=true''' if true, returns when the item has been updated in server memory.
   *        If false, returns only after item has also been written to server disk. Default is false.
   *  - '''"ring"="ringName"''' write to this ring.
   */
  def delete(key: JsonKey, options: JsonObject = emptyJsonObject): Unit = {
    val f1 = asyncClient.delete(key, options)
    Await.result(f1, 5 seconds)
  }

  /**
   * Gets information about an item with a specified key.
   *
   * @param key the key.
   * @param options optional json object containing options.
   *  - '''"get="kvc"''' if specified this method returns an object with requested fields
   *      (Key, Value, vector Clock).
   *  - '''"r"=n''' read from at least n rings before returning. Default is 1.
   *  - '''"ring"="ringName"''' get from this ring.
   *
   * @return None if there is no item with that key, or Some(X) if the item exists.
   * X will be the value of the item if the get option is not specified;
   * otherwise, an object with the fields specified in the get option.
   */
  def get(key: JsonKey, options: JsonObject = emptyJsonObject): Option[Json] = {
    val f = asyncClient.get(key, options)
    val j = Await.result(f, 5 seconds)
    j
  }

  /**
   *
   * Returns an iterator that iterates over items in the table.
   * If options are not specified it will iterate over all items
   * in the table returning their keys.
   *
   * @param options optional json object containing options.
   *    Options can modify what items are returned and what information is
   *    returned for each item.
   *  - '''"reverse"=true''' if true, return keys in reverse order. Default is false.
   *  - '''"get="kvc"''' if specified this method returns an object with requested fields
   *      (Key, Value, vector Clock).
   *  - '''"low"=key''' the lowest key that should be returned.
   *  - '''"includelow"=true''' if true, the low option is inclusive.
   *  If false, the low option is exclusive. Default is false.
   *  - '''"high"=key''' the highest key that should be returned.
   *  - '''"includehigh"=true''' if true, the high option is inclusive.
   *  If false, the high option is exclusive. Default is false.
   *  - '''"prefix"=key''' only keys for which this is a prefix are returned.
   *  - '''"includeprefix"=true''' if true, any key equal to the prefix is included.
   *  If false, any key equal to the prefix is not included. Default is false.
   *  - '''"parent"=key''' only keys which are direct children of this key are included.
   *  This option permits trees (where keys are arrays that represents paths) to be traversed.
   *  - '''"includeparent"=true''' if true, any key equal to the parent is also included.
   *  If false, any key equal to the parent is not included. Default is false.
      *  - '''"ring"="ringName"''' get items from this ring.
*
   *  @return the iterator.
   *
   */
  def all(options: JsonObject = emptyJsonObject): AllItems = {
    SyncAllItems(asyncClient, options)
  }

  /**
   * Temporary debugging method.
   */
  def report(): Json = {
    var result = JsonObject()
    for ((ringName,ringConfig) <- config.rings) {
      var ro = JsonObject()
      val dest = Map("ring" -> ringName)
      // TODO could do calls in parallel
      for (nodeName<- ringConfig.nodes.keys) {
        val dest1 = dest + ("node" -> nodeName)
        var f1 = new DefaultPromise[Any]
        send ! ("report", dest1, f1, tableName, "", "")
        val (code: String, x: String) = Await.result(f1, 5 seconds)
        ro = ro + (nodeName -> Json(x))
      }
      result = result + (ringName -> ro)
    }
    result
  }

  /**
   * Temporary debugging method
   */
  def monitor(): Json = {
    var result = JsonObject()
    for ((ringName,ringConfig) <- config.rings) {
      var ro = JsonObject()
      // TODO could do calls in parallel
      for ((nodeName,nodeConfig)<-ringConfig.nodes) {
        implicit val timeout = Timeout(5 seconds)
        val monitorRef: ActorRef = system.actorFor("akka://ostore@" + nodeConfig.host + ":" + nodeConfig.port + 
            "/user/" + config.name + "/" + ringName + "/" + nodeName + "/@mon")
        val f1 = monitorRef ? ("get", tableName)
        val (code: String, x: String) = Await.result(f1, 5 seconds)
        ro = ro + (nodeName -> Json(x))
      }
      result = result + (ringName -> ro)
    }
    result
  }
}