/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package com.hypertino.services.authpin.utils

object ErrorCode {
  final val FORMAT_ERROR_PIN_ID  = "format_error_pin_id"
  final val FORMAT_ERROR = "format_error"
  final val PIN_IS_CONSUMED_OR_EXPIRED = "pin_is_consumed_or_expired"
  final val PIN_IS_NOT_VALID = "pin_is_not_valid"
  final val PIN_NOT_FOUND = "pin_not_found"
  final val PIN_LENGTH_IS_TOO_SMALL = "pin_length_is_too_small"
}
