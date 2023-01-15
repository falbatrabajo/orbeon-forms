/**
  * Copyright (C) 2019 Orbeon, Inc.
  *
  * This program is free software; you can redistribute it and/or modify it under the terms of the
  * GNU Lesser General Public License as published by the Free Software Foundation; either version
  *  2.1 of the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  * See the GNU Lesser General Public License for more details.
  *
  * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  */
package org.orbeon.date

import cats.syntax.option._
import org.orbeon.date.IsoTime._
import org.orbeon.date.JSDateUtils._
import org.scalatest.funspec.AnyFunSpec

import scala.scalajs.js

class JSDateUtilsTest extends AnyFunSpec {

  // In the list of strings, the first strings is the canonical form, that is the one we expect when
  // the `js.Date` is converted to a string.
  val StringsToDates = List(
    List("2032-05-29"                    ) -> Some(new js.Date(2032,  4, 29, 0, 0, 0, 0)),
    List("2032-12-31"                    ) -> Some(new js.Date(2032, 11, 31, 0, 0, 0, 0)),
    List("2019-01-01", "2019-01-01-08:00") -> Some(new js.Date(2019,  0,  1, 0, 0, 0, 0)),
    List("2021-13-01"                    ) -> None, // There is no month 13
    List("2021-11-32"                    ) -> None, // There is no day 32
    List("2021-11-31"                    ) -> None  // November has 30 days
  )

  describe("ISO string conversion to JavaScript date") {
    for {
      (isoStrings, expectedDate) <- StringsToDates
      isoString                  <- isoStrings
    }
      it(s"must pass for `$isoString`") {
        val actual   = parseIsoDateUsingLocalTimezone(isoString).map(_.getTime)
        val expected = expectedDate.map(_.getTime)
        assert(actual === expected)
      }
  }

  describe("JavaScript date conversion to ISO string") {
    for {
      (isoStrings, dateOpt) <- StringsToDates
      expectedString        = isoStrings.head
      date                  <- dateOpt
    }
      it(s"must pass for `$expectedString`") {
        assert(dateToIsoStringUsingLocalTimezone(date) === expectedString)
      }
  }

  describe("Roundtrip with current date") {
    it(s"must return the same result") {

      val current = new js.Date()

      current.setHours(0)
      current.setMinutes(0)
      current.setSeconds(0)
      current.setMilliseconds(0)

      assert(parseIsoDateUsingLocalTimezone(dateToIsoStringUsingLocalTimezone(current)) map (_.getTime) contains current.getTime)
    }
  }

  describe("Magic time parsing") {

    val NowIsoTime = IsoTime(14, 9, 27.some)  // some randomly chosen time

    val StringsToIsoDates = List(
      "now"           -> NowIsoTime.some,
      "21:22:23"      -> IsoTime(21, 22, 23.some).some,
      "12:34:56 p.m." -> IsoTime(12, 34, 56.some).some,
      "12:34 p.m."    -> IsoTime(12, 34, None).some,
      "12 p.m."       -> IsoTime(12, 0,  None).some, // by convention
      "12:34:56 a.m." -> IsoTime(0,  34, 56.some).some,
      "12:34 a.m."    -> IsoTime(0,  34, None).some,
      "12 a.m."       -> IsoTime(0,  0,  None).some, // by convention
      "12:34:56p.m."  -> IsoTime(12, 34, 56.some).some,
      "12:34p.m."     -> IsoTime(12, 34, None).some,
      "12p.m."        -> IsoTime(12, 0,  None).some,
      "12:34:56a.m."  -> IsoTime(0,  34, 56.some).some,
      "12:34a.m."     -> IsoTime(0,  34, None).some,
      "12a.m."        -> IsoTime(0,  0,  None).some,
      "12:34:56pm"    -> IsoTime(12, 34, 56.some).some,
      "12:34pm"       -> IsoTime(12, 34, None).some,
      "12pm"          -> IsoTime(12, 0,  None).some,
      "12:34:56am"    -> IsoTime(0,  34, 56.some).some,
      "12:34am"       -> IsoTime(0,  34, None).some,
      "12am"          -> IsoTime(0,  0,  None).some,
      "0"             -> IsoTime(0,  0,  None).some,
//      "24"            -> IsoTime(0,  0,  None).some,
      // Reject invalid strings, including out of range to avoid silent autocorrection
      "foo"           -> None,
      "25:22:23"      -> None,
      "21:60:23"      -> None,
      "21:22:60"      -> None,
    )

    for ((s, expected) <- StringsToIsoDates)
      it(s"must pass for `$s`") {
        assert(findMagicTimeAsIsoTime(s, NowIsoTime) == expected)
      }
  }

  describe("Time formatting") {

    val FormatWithSecondsAndLowerDotsAmPm   = "[h]:[m]:[s] [P]"
    val FormatWithSecondsAndUpperDotsAmPm   = "[h]:[m]:[s] [PN]"
    val FormatWithSecondsAndLowerAmPm       = "[h]:[m]:[s] [P,2-2]"
    val FormatWithSecondsAndUpperAmPm       = "[h]:[m]:[s] [PN,2-2]"
    val FormatWithSecondsAndLowerShortAmPm  = "[h]:[m]:[s] [P,1-1]"
    val FormatWithSecondsAndUpperShortAmPm  = "[h]:[m]:[s] [PN,1-1]"
    val FormatWithSeconds24Hour             = "[H]:[m]:[s]"
    val FormatWithSeconds24Hour2Digits      = "[H01]:[m]:[s]"

    val FormatNoSecondsAndLowerDotsAmPm     = "[h]:[m] [P]"
    val FormatNoSecondsAndUpperDotsAmPm     = "[h]:[m] [PN]"
    val FormatNoSecondsAndLowerAmPm         = "[h]:[m] [P,2-2]"
    val FormatNoSecondsAndUpperAmPm         = "[h]:[m] [PN,2-2]"
    val FormatNoSecondsAndLowerShortAmPm    = "[h]:[m] [P,1-1]"
    val FormatNoSecondsAndUpperShortAmPm    = "[h]:[m] [PN,1-1]"
    val FormatNoSeconds24Hour               = "[H]:[m]"
    val FormatNoSeconds24Hour2Digits        = "[H01]:[m]"

    val StringsToIsoDates = List(
      (IsoTime(0,  22, 23.some), FormatWithSecondsAndLowerDotsAmPm)   -> "12:22:23 a.m.",
      (IsoTime(0,  22, 23.some), FormatWithSecondsAndUpperDotsAmPm)   -> "12:22:23 A.M.",
      (IsoTime(0,  22, 23.some), FormatWithSecondsAndLowerAmPm)       -> "12:22:23 am",
      (IsoTime(0,  22, 23.some), FormatWithSecondsAndUpperAmPm)       -> "12:22:23 AM",
      (IsoTime(0,  22, 23.some), FormatWithSecondsAndLowerShortAmPm)  -> "12:22:23 a",
      (IsoTime(0,  22, 23.some), FormatWithSecondsAndUpperShortAmPm)  -> "12:22:23 A",
      (IsoTime(0,  22, 23.some), FormatWithSeconds24Hour)             -> "0:22:23",
      (IsoTime(3,  22, 23.some), FormatWithSecondsAndLowerDotsAmPm)   -> "3:22:23 a.m.",
      (IsoTime(3,  22, 23.some), FormatWithSecondsAndLowerAmPm)       -> "3:22:23 am",
      (IsoTime(3,  22, 23.some), FormatWithSeconds24Hour)             -> "3:22:23",
      (IsoTime(12, 22, 23.some), FormatWithSecondsAndLowerDotsAmPm)   -> "12:22:23 p.m.",
      (IsoTime(12, 22, 23.some), FormatWithSecondsAndLowerAmPm)       -> "12:22:23 pm",
      (IsoTime(12, 22, 23.some), FormatWithSeconds24Hour)             -> "12:22:23",
      (IsoTime(21, 22, 23.some), FormatWithSecondsAndLowerDotsAmPm)   -> "9:22:23 p.m.",
      (IsoTime(21, 22, 23.some), FormatWithSecondsAndLowerAmPm)       -> "9:22:23 pm",
      (IsoTime(21, 22, 23.some), FormatWithSeconds24Hour)             -> "21:22:23",
      (IsoTime(1,   2,  3.some), FormatWithSecondsAndLowerDotsAmPm)   -> "1:02:03 a.m.",
      (IsoTime(1,   2,  3.some), FormatWithSecondsAndLowerAmPm)       -> "1:02:03 am",
      (IsoTime(1,   2,  3.some), FormatWithSeconds24Hour)             -> "1:02:03",
      (IsoTime(1,   2,  3.some), FormatWithSeconds24Hour2Digits)      -> "01:02:03",

      (IsoTime(0,  22, 23.some), FormatNoSecondsAndLowerDotsAmPm)     -> "12:22 a.m.",
      (IsoTime(0,  22, 23.some), FormatNoSecondsAndUpperDotsAmPm)     -> "12:22 A.M.",
      (IsoTime(0,  22, 23.some), FormatNoSecondsAndLowerAmPm)         -> "12:22 am",
      (IsoTime(0,  22, 23.some), FormatNoSecondsAndUpperAmPm)         -> "12:22 AM",
      (IsoTime(0,  22, 23.some), FormatNoSecondsAndLowerShortAmPm)    -> "12:22 a",
      (IsoTime(0,  22, 23.some), FormatNoSecondsAndUpperShortAmPm)    -> "12:22 A",
      (IsoTime(0,  22, 23.some), FormatNoSeconds24Hour)               -> "0:22",
      (IsoTime(3,  22, 23.some), FormatNoSecondsAndLowerDotsAmPm)     -> "3:22 a.m.",
      (IsoTime(3,  22, 23.some), FormatNoSecondsAndLowerAmPm)         -> "3:22 am",
      (IsoTime(3,  22, 23.some), FormatNoSeconds24Hour)               -> "3:22",
      (IsoTime(12, 22, 23.some), FormatNoSecondsAndLowerDotsAmPm)     -> "12:22 p.m.",
      (IsoTime(12, 22, 23.some), FormatNoSecondsAndLowerAmPm)         -> "12:22 pm",
      (IsoTime(12, 22, 23.some), FormatNoSeconds24Hour)               -> "12:22",
      (IsoTime(21, 22, 23.some), FormatNoSecondsAndLowerDotsAmPm)     -> "9:22 p.m.",
      (IsoTime(21, 22, 23.some), FormatNoSecondsAndLowerAmPm)         -> "9:22 pm",
      (IsoTime(21, 22, 23.some), FormatNoSeconds24Hour)               -> "21:22",
      (IsoTime(1,   2,  3.some), FormatNoSecondsAndLowerDotsAmPm)     -> "1:02 a.m.",
      (IsoTime(1,   2,  3.some), FormatNoSecondsAndLowerAmPm)         -> "1:02 am",
      (IsoTime(1,   2,  3.some), FormatNoSeconds24Hour)               -> "1:02",
      (IsoTime(1,   2,  3.some), FormatNoSeconds24Hour2Digits)        -> "01:02",
    )

    for (((s, format), expected) <- StringsToIsoDates)
      it(s"must pass for `${s.toIsoString}` with format `$format`") {
        assert(formatTime(s, parseFormat(format)) == expected)
      }
  }

  describe("Picture string generation") {

    val Formats = List(
      TimeFormat(is24Hour = false, isPadHourDigits = false, hasSeconds = true,  AmPmFormat.Lower)      -> "[h]:[m]:[s] [P,2-2]",
      TimeFormat(is24Hour = false, isPadHourDigits = false, hasSeconds = true,  AmPmFormat.Upper)      -> "[h]:[m]:[s] [PN,2-2]",
      TimeFormat(is24Hour = false, isPadHourDigits = false, hasSeconds = true,  AmPmFormat.LowerDots)  -> "[h]:[m]:[s] [P]",
      TimeFormat(is24Hour = false, isPadHourDigits = false, hasSeconds = true,  AmPmFormat.LowerShort) -> "[h]:[m]:[s] [P,1-1]",
      TimeFormat(is24Hour = false, isPadHourDigits = false, hasSeconds = true,  AmPmFormat.UpperDots)  -> "[h]:[m]:[s] [PN]",
      TimeFormat(is24Hour = false, isPadHourDigits = false, hasSeconds = true,  AmPmFormat.UpperShort) -> "[h]:[m]:[s] [PN,1-1]",
      TimeFormat(is24Hour = true,  isPadHourDigits = false, hasSeconds = true,  AmPmFormat.None)       -> "[H]:[m]:[s]",
      TimeFormat(is24Hour = true,  isPadHourDigits = true,  hasSeconds = true,  AmPmFormat.None)       -> "[H01]:[m]:[s]",

      TimeFormat(is24Hour = false, isPadHourDigits = false, hasSeconds = false, AmPmFormat.Lower)      -> "[h]:[m] [P,2-2]",
      TimeFormat(is24Hour = false, isPadHourDigits = false, hasSeconds = false, AmPmFormat.Upper)      -> "[h]:[m] [PN,2-2]",
      TimeFormat(is24Hour = false, isPadHourDigits = false, hasSeconds = false, AmPmFormat.LowerDots)  -> "[h]:[m] [P]",
      TimeFormat(is24Hour = false, isPadHourDigits = false, hasSeconds = false, AmPmFormat.LowerShort) -> "[h]:[m] [P,1-1]",
      TimeFormat(is24Hour = false, isPadHourDigits = false, hasSeconds = false, AmPmFormat.UpperDots)  -> "[h]:[m] [PN]",
      TimeFormat(is24Hour = false, isPadHourDigits = false, hasSeconds = false, AmPmFormat.UpperShort) -> "[h]:[m] [PN,1-1]",
      TimeFormat(is24Hour = true,  isPadHourDigits = false, hasSeconds = false, AmPmFormat.None)       -> "[H]:[m]",
      TimeFormat(is24Hour = true,  isPadHourDigits = true,  hasSeconds = false, AmPmFormat.None)       -> "[H01]:[m]",
    )

    for ((timeFormat, timeFormatString) <- Formats)
      it(s"must pass for `$timeFormat`") {
        assert(generateFormat(timeFormat) == timeFormatString)
      }
  }
}
