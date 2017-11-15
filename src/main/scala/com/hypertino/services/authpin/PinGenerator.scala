/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package com.hypertino.services.authpin

import java.security.SecureRandom

import com.hypertino.hyperbus.util.IdGeneratorBase

class PinGenerator() extends IdGeneratorBase{
  private val random = new SecureRandom()

  def nextPin(pinSize: Int, onlyDigits: Boolean): String = {
    val sb = new StringBuilder(pinSize)
    0 until pinSize foreach { _ ⇒
      val char = if (onlyDigits)
        (random.nextInt(10) + 48).toChar
      else
        base64t.charAt(random.nextInt(base64t.length))
      sb.append(char)
    }
    sb.toString
  }
}
