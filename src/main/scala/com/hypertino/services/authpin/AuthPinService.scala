/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package com.hypertino.services.authpin

import java.net.URLEncoder
import java.util.Base64

import com.hypertino.authpin.api._
import com.hypertino.authpin.apiref.hyperstorage._
import com.hypertino.binders.value.{Null, Obj}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{BadRequest, Created, DynamicBody, ErrorBody, Header, Headers, NoContent, NotFound, ResponseBase, Unauthorized}
import com.hypertino.hyperbus.subscribe.Subscribable
import com.hypertino.hyperbus.util.IdGenerator
import com.hypertino.service.control.api.Service
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Task
import monix.execution.Scheduler
import scaldi.{Injectable, Injector}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class AuthPinService(implicit val injector: Injector) extends Service with Injectable with Subscribable with StrictLogging {
  private implicit val scheduler = inject[Scheduler]
  private val hyperbus = inject[Hyperbus]
  private final val DEFAULT_PIN_LIFETIME = 24 * 60 * 60
  private final val MAX_ATTEMPTS = 3
  private final val pinGenerator = new PinGenerator()
  logger.info(s"${getClass.getName} is STARTED")

  private val handlers = hyperbus.subscribe(this, logger)

  def onValidationsPost(implicit post: ValidationsPost): Task[ResponseBase] = {
    val authorization = post.body.authorization
    val spaceIndex = authorization.indexOf(" ")
    if (spaceIndex < 0 || authorization.substring(0, spaceIndex).compareToIgnoreCase("pin") != 0) {
      Task.eval(BadRequest(ErrorBody("format-error")))
    }
    else {
      val base64 = authorization.substring(spaceIndex + 1)
      val pinIdAndValue = new String(Base64.getDecoder.decode(base64), "UTF-8")
      val semicolonIndex = pinIdAndValue.indexOf(":")
      if (semicolonIndex < 0) {
        Task.eval(BadRequest(ErrorBody("format-error-pin-id")))
      }
      else {
        val pinId = pinIdAndValue.substring(0, semicolonIndex)
        val pinValue = pinIdAndValue.substring(semicolonIndex + 1)
        hyperbus
          .ask(ContentGet(getPinStoragePath(pinId)))
          .flatMap { existingPin ⇒
            import com.hypertino.binders.value._
            import com.hypertino.hyperbus.serialization.SerializationOptions.default._
            val pinObject = existingPin.body.content.to[Pin]
            val pinETag = existingPin.headers(HyperStorageHeader.ETAG)
            if (pinObject.consumed
              || pinObject.attempts >= MAX_ATTEMPTS
              || pinObject.validUntil < System.currentTimeMillis
            ) {
              Task.raiseError(Unauthorized(ErrorBody("pin-is-consumed-or-expired")))
            }
            else
            if (pinObject.pin != pinValue) {
              hyperbus
                .ask(ContentPatch(getPinStoragePath(pinId),
                  DynamicBody(Obj.from("attempts" → 1)),
                  headers=Headers(Header.CONTENT_TYPE → HyperStoragePatchType.HYPERSTORAGE_CONTENT_INCREMENT))
                ).flatMap { _ ⇒
                Task.raiseError(Unauthorized(ErrorBody("pin-is-not-valid")))
              }
            }
            else {
              hyperbus
                .ask(ContentPatch(getPinStoragePath(pinId),
                  DynamicBody(Obj.from("consumed" → true)),
                  headers=Headers(HyperStorageHeader.IF_MATCH → pinETag))
                ).map { _ ⇒
                Created(ValidationResult(
                  identityKeys = pinObject.identityKeys,
                  extra = pinObject.extra
                ))
              }
            }
          }
          .onErrorRecover {
            case _: NotFound[_] ⇒
              Unauthorized(ErrorBody("pin-not-found"))
          }
      }
    }
  }

  def onPinsPost(implicit post: PinsPost): Task[ResponseBase] = {
    if (post.body.pinLength > 2) {
      val pinId = IdGenerator.create()
      val pin = pinGenerator.nextPin(post.body.pinLength, post.body.onlyDigits)
      val ttlInSeconds = post.body.timeToLiveSeconds.getOrElse(DEFAULT_PIN_LIFETIME)
      val validUntil = ttlInSeconds.toLong * 1000l + System.currentTimeMillis()

      val pinObject = Pin(pin, post.body.identityKeys, post.body.extra, 0, consumed = false, validUntil)

      import com.hypertino.binders.value._
      import com.hypertino.hyperbus.serialization.SerializationOptions.default._
      hyperbus
        .ask(ContentPut(getPinStoragePath(pinId), DynamicBody(pinObject.toValue), headers = Headers(
          HyperStorageHeader.HYPER_STORAGE_TTL → ttlInSeconds
        )))
        .map { _ ⇒
          Created(NewPin(pinId))
        }
    } else {
      Task.raiseError(BadRequest(ErrorBody("pin-length-is-too-small", Some("Pin can't be shorter than 3 symbols"))))
    }
  }

  private def getPinStoragePath(pinId: String) = s"auth-pin-service/pins/$pinId"

  override def stopService(controlBreak: Boolean, timeout: FiniteDuration): Future[Unit] = Future {
    handlers.foreach(_.cancel())
    logger.info(s"${getClass.getName} is STOPPED")
  }
}
