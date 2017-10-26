/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package com.hypertino.services.authpin

import java.util.Base64

import com.hypertino.authpin.api.{CreatePin, PinsPost, Validation, ValidationsPost}
import com.hypertino.authpin.apiref.hyperstorage._
import com.hypertino.binders.value.{Null, Obj, Value}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{Created, DynamicBody, ErrorBody, Headers, MessagingContext, NotFound, Ok, ResponseBase, Unauthorized}
import com.hypertino.hyperbus.subscribe.Subscribable
import com.hypertino.hyperbus.transport.api.ServiceRegistrator
import com.hypertino.hyperbus.transport.registrators.DummyRegistrator
import com.hypertino.hyperbus.util.IdGenerator
import com.hypertino.service.config.ConfigLoader
import com.typesafe.config.Config
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import scaldi.Module

import scala.collection.mutable
import scala.concurrent.duration._

class AuthPinServiceSpec extends FlatSpec with Module with BeforeAndAfterAll with ScalaFutures with Matchers with Subscribable {
  override implicit val patienceConfig = PatienceConfig(timeout = scaled(Span(3, Seconds)))
  implicit val scheduler = monix.execution.Scheduler.Implicits.global
  implicit val mcx = MessagingContext.empty
  bind [Config] to ConfigLoader()
  bind [Scheduler] to scheduler
  bind [Hyperbus] to injected[Hyperbus]
  bind [ServiceRegistrator] to DummyRegistrator

  val hyperbus = inject[Hyperbus]
  val handlers = hyperbus.subscribe(this)
  Thread.sleep(500)

  val hyperStorageContent = mutable.Map[String, Value]()

  def onContentPut(implicit request: ContentPut): Task[ResponseBase] = {
    if (hyperStorageContent.put(request.path, request.body.content).isDefined) {
      Task.eval(Ok(HyperStorageTransaction("100500",request.path, 1l)))
    }
    else {
      Task.eval(Created(HyperStorageTransaction("100500",request.path, 1l)))
    }
  }

  def onContentPatch(implicit request: ContentPatch): Task[ResponseBase] = {
    hyperStorageContent.get(request.path) match {
      case Some(v) ⇒
        if (request.headers.contentType.contains(HyperStoragePatchType.HYPERSTORAGE_CONTENT_INCREMENT))
          hyperStorageContent.put(request.path, v + request.body.content)
        else
          hyperStorageContent.put(request.path, v % request.body.content)
        Task.eval(Ok(HyperStorageTransaction("100500",request.path, 1l)))

      case None ⇒
        hyperStorageContent.put(request.path, request.body.content)
        Task.eval(Created(HyperStorageTransaction("100500",request.path, 1l)))
    }
  }

  def onContentGet(implicit request: ContentGet): Task[ResponseBase] = {
    hyperStorageContent.get(request.path) match {
      case Some(v) ⇒ Task.eval(Ok(DynamicBody(v),Headers(HyperStorageHeader.ETAG → v.hashCode().toString)))
      case None ⇒ Task.eval(NotFound(ErrorBody("not-found", Some(request.path))))
    }
  }

  def onContentDelete(implicit request: ContentDelete): Task[ResponseBase] = {
    hyperStorageContent.get(request.path)
    Task.eval(Ok(HyperStorageTransaction("100500",request.path, 1l)))
  }
  val service = new AuthPinService()

  override def afterAll() {
    service.stopService(false, 10.seconds).futureValue
    hyperbus.shutdown(10.seconds).runAsync.futureValue
  }

  def selectPin(pinId: String): Value = hyperStorageContent(s"auth-pin-service/pins/$pinId")

  "AuthPinService" should "create pin" in {
    val pinId = IdGenerator.create()
    val c = hyperbus
      .ask(PinsPost(CreatePin(pinId,Some(60),Some(3),onlyDigits = Some(false),Obj.from("user_id" → "100500"), Null)))
      .runAsync
      .futureValue

    val v = selectPin(c.body.pinId)
    v.dynamic.pin.toString.length shouldBe 3
    c.body.pin shouldBe v.dynamic.pin.toString
    c.body.pinId shouldBe pinId
    v.dynamic.identity_keys.dynamic.user_id.toString shouldBe "100500"
  }

  it should "authorize and consume if pin matches" in {
    val pinId = IdGenerator.create()
    val c = hyperbus
      .ask(PinsPost(CreatePin(pinId, Some(60),Some(3),onlyDigits = None,Obj.from("user_id" → "100500"), Null)))
      .runAsync
      .futureValue

    val v = selectPin(c.body.pinId)
    c.body.pin shouldBe v.dynamic.pin.toString
    c.body.pinId shouldBe pinId
    v.dynamic.pin.toString.length shouldBe 3
    v.dynamic.identity_keys.dynamic.user_id.toString shouldBe "100500"

    val credentials = c.body.pinId + ":" + v.dynamic.pin.toString
    val authHeader = new String(Base64.getEncoder.encode(credentials.getBytes("UTF-8")), "UTF-8")

    val r = hyperbus
      .ask(ValidationsPost(Validation(s"Pin $authHeader")))
      .runAsync
      .futureValue

    r shouldBe a[Created[_]]
    r.body.identityKeys shouldBe Obj.from("user_id" → "100500")

    val r2 = hyperbus
      .ask(ValidationsPost(Validation(s"Pin $authHeader")))
      .runAsync
      .failed
      .futureValue

    r2 shouldBe a[Unauthorized[_]]
    val b = r2.asInstanceOf[Unauthorized[ErrorBody]].body
    b.code shouldBe "pin-is-consumed-or-expired"
  }

  it should "unathorize if user pin doesn't exists" in {
    val r = hyperbus
      .ask(ValidationsPost(Validation("Pin bWFtbW90aDoxMjM0NQ==")))
      .runAsync
      .failed
      .futureValue

    r shouldBe a[Unauthorized[_]]
    val b = r.asInstanceOf[Unauthorized[ErrorBody]].body
    b.code shouldBe "pin-not-found"
  }

  it should "invalidate pin after 3 attempts" in {
    val pinId = IdGenerator.create()
    val c = hyperbus
      .ask(PinsPost(CreatePin(pinId, Some(60),Some(3),onlyDigits = Some(true),Obj.from("user_id" → "100500"), Null)))
      .runAsync
      .futureValue

    val v = selectPin(c.body.pinId)
    v.dynamic.pin.toString.length shouldBe 3
    v.dynamic.identity_keys.dynamic.user_id.toString shouldBe "100500"
    c.body.pin shouldBe v.dynamic.pin.toString
    c.body.pinId shouldBe pinId

    val credentials = c.body.pinId + ":" + v.dynamic.pin.toString + "1"
    val authHeader = new String(Base64.getEncoder.encode(credentials.getBytes("UTF-8")), "UTF-8")

    def attempt() = {
      val r = hyperbus
        .ask(ValidationsPost(Validation(s"Pin $authHeader")))
        .runAsync
        .failed
        .futureValue

      r shouldBe a[Unauthorized[_]]
      val b = r.asInstanceOf[Unauthorized[ErrorBody]].body
      b.code
    }
    attempt() shouldBe "pin-is-not-valid"
    attempt() shouldBe "pin-is-not-valid"
    attempt() shouldBe "pin-is-not-valid"
    attempt() shouldBe "pin-is-consumed-or-expired"

    val credentials2 = c.body.pinId + ":" + v.dynamic.pin.toString
    val authHeader2 = new String(Base64.getEncoder.encode(credentials2.getBytes("UTF-8")), "UTF-8")

    val r = hyperbus
      .ask(ValidationsPost(Validation(s"Pin $authHeader")))
      .runAsync
      .failed
      .futureValue

    r shouldBe a[Unauthorized[_]]
    val b = r.asInstanceOf[Unauthorized[ErrorBody]].body
    b.code shouldBe "pin-is-consumed-or-expired"
  }

  it should "expire after ttl" in {
    val pinId = IdGenerator.create()
    val c = hyperbus
      .ask(PinsPost(CreatePin(pinId, Some(1),Some(3),onlyDigits = Some(true),Obj.from("user_id" → "100500"), Null)))
      .runAsync
      .futureValue

    val v = selectPin(c.body.pinId)
    v.dynamic.pin.toString.length shouldBe 3
    v.dynamic.identity_keys.dynamic.user_id.toString shouldBe "100500"
    c.body.pin shouldBe v.dynamic.pin.toString
    c.body.pinId shouldBe pinId

    Thread.sleep(3000)

    val credentials = c.body.pinId + ":" + v.dynamic.pin.toString
    val authHeader = new String(Base64.getEncoder.encode(credentials.getBytes("UTF-8")), "UTF-8")

    val r = hyperbus
      .ask(ValidationsPost(Validation(s"Pin $authHeader")))
      .runAsync
      .failed
      .futureValue

    r shouldBe a[Unauthorized[_]]
    val b = r.asInstanceOf[Unauthorized[ErrorBody]].body
    b.code shouldBe "pin-is-consumed-or-expired"
  }
}
