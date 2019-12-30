/*
 * Copyright 2019 AstroLab Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.astrolabsoftware.sparkfits.utils

import com.astrolabsoftware.sparkfits.FitsLib
import com.astrolabsoftware.sparkfits.FitsLib.Fits
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.log4j.LogManager
import org.apache.spark.sql.execution.datasources.PartitionedFile

import scala.util.Try

class FitsMetadata(partitionedFile: PartitionedFile, val index: Int, conf: Configuration) {

  val log = LogManager.getRootLogger
  val path = new Path(partitionedFile.filePath)
  private[sparkfits] val fits = new Fits(path, conf, conf.getInt("hdu", -1))
  private[sparkfits] val startStop = fits.blockBoundaries
  private val header = fits.blockHeader
  private[sparkfits] var notValid = false
  val keyValues = FitsLib.parseHeader(header)
  if (keyValues("NAXIS").toInt == 0) {
    conf.get("mode") match {
      case "PERMISSIVE" =>
        log.warn(s"Empty HDU for ${path}")
        notValid = true
      case "FAILFAST" =>
        log.warn(s"Empty HDU for ${path}")
        log.warn(s"Use option('mode', 'PERMISSIVE') if you want to discard all empty HDUs.")
      case _ =>
    }
  }

  private var recordLength: Long = 0L
  var rowSizeInt: Int = 0
  var rowSizeLong: Long = 0L

  if (!notValid) {

    val nrowsLong = fits.hdu.getNRows(keyValues)
    rowSizeInt = fits.hdu.getSizeRowBytes(keyValues)
    rowSizeLong = rowSizeInt.toLong


    // Get the record length in Bytes (get integer!). First look if the user
    // specify a size for the recordLength. If not, set it to max(1 Ko, rowSize).
    // If the HDU is an image, the recordLength is the row size (NAXIS1 * nbytes)
    val recordLengthFromUser = Try {
      conf.get("recordlength").toInt
    }
      .getOrElse {
        if (fits.hduType == "IMAGE") {
          rowSizeInt
        } else {
          // set it to max(1 Ko, rowSize)
          math.max((1 * 1024 / rowSizeInt) * rowSizeInt, rowSizeInt)
        }
      }


    // For Table, seek for a round number of lines for the record
    // ToDo: Cases when the user has given the record length
    recordLength = (recordLengthFromUser / rowSizeInt) * rowSizeInt

    // Make sure that the recordLength is not bigger than the block size!
    // This is a guard for small files.
    recordLength = if ((recordLength / rowSizeInt) < nrowsLong.toInt) {
      // OK less than the total number of lines
      recordLength
    } else {
      // Small files, one record is the entire file.
      nrowsLong.toInt * rowSizeLong.toInt
    }
    // Move to the starting binary index
    fits.data.seek(startStop.dataStart)
  }
}
