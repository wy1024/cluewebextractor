package edu.knowitall.cluewebextractor

import de.l3s.boilerpipe.extractors
import edu.washington.cs.knowitall.tool.sentence.OpenNlpSentencer
import edu.washington.cs.knowitall.common.Timing
import edu.washington.cs.knowitall.common.Resource
import scala.collection.JavaConverters._
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.FileWriter
import java.io.PrintStream
import java.io.InputStream
import java.io.FileInputStream
import java.io.File
import java.io.PrintWriter
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.DataInputStream
import java.util.zip.GZIPInputStream
import java.io.InputStream

/**
 *    Copyright 2013 David H Jung
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
 *
 * ----------------------- END OF LICENSE INFO --------------------------------
 *
 * CLI that takes as input either a .warc file or directory containing .warc
 * files and outputs the extracted payload content in either a default or
 * specified directory.
 *
 * If the user inputs a directory that contains a .warc file with an already-
 * existing corresponding output file, it will be skipped.
 *
 * If the user inputs a single .warc file with an already-existing
 * corresponding output file, it will be overwritten.
 *
 * On bad input or unexpected errors, this program will choose to log the error
 * and skip over the file (or document, depending on the granularity of the
 * error) rather than stop execution.
 */
object CluewebExtractorMain extends App {
  val logger = LoggerFactory.getLogger(this.getClass)

  // What we use to process the warc records
  val garbager = new GarbageFilter()
  val nlpSentencer = new OpenNlpSentencer()
  val bp = new extractors.DefaultExtractor()

  case class Config(
    inputType: String = "", //the type of input, wiki or warc
    inputFiles: Seq[File] = Seq.empty,
    outputDirectory: Option[File] = None) {}

  // Defines the command line arguments.
  val parser = new scopt.immutable.OptionParser[Config]("cweb") {
    def options = Seq(
      arglist("<input-files>", "pattern file") { (path: String, config: Config) =>
        val file = new File(path)
        require(file.exists(), "file does not exist: " + path)
        config.copy(inputFiles = (config.inputFiles :+ file))
      },
      //take in an argument before the list of filenames
      opt("input-type", "type of input") { (v: String, config: Config) =>
        config.copy(inputType = v)
      },
      opt("output-dir", "output directory") { (path: String, config: Config) =>
        val file = new File(path)
        require(file.exists, "directory does not exist: " + path)
        require(file.isDirectory, "file is not a directory: " + path)
        config.copy(outputDirectory = Some(file))
      })
  }

  parser.parse(args, Config()) match {
    case Some(config) => run(config)
    case None => System.err.println(usage)
  }

  def run(config: Config) {

    // Files contains (inputFile, outputFile) pairs.
    val files: Iterable[(File, File)] = getInputOutputFiles(config)

    // For each (input, output) pair, get a warc record iterator for the input
    // and write the corresponding extracted payload to the output
    for ((inputFile, outputFile) <- files) {
      try {
        //added input type when processing a warc file
        processWarcFile(inputFile, outputFile, config.inputType)
      } catch {
        case e: Throwable =>
          logger.error("Error while processing warc file: " + inputFile +
            ". Skipping file. \n\t" + e + ": " + e.getStackTraceString);
      }

    }
  }

  // Given an input warc file and its corresponding output file, processes the
  // input and writes out the payloads to outputFile.
  def processWarcFile(inputFile: File, outputFile: File, inputType: String) = {

    val ns = Timing.time {
      Resource.using(openInputStream(inputFile)) { is =>
        Resource.using(new PrintWriter(outputFile, "UTF8")) { writer =>

          val warcIt: Iterator[Option[WarcRecord]] = inputType match {
            case "wiki" => new WikiIterator(new BufferedInputStream(is))

            case "warc" => new WarcRecordIterator(
              new DataInputStream(
                new BufferedInputStream(is)))
          }

          //print the type of interator created
          logger.info("Successfully created new " + inputType + " iterator")

          var nanos = System.nanoTime()
          var i = 0

          // Iterate over warc records
          for (warc <- warcIt.flatten) {
            if (warc.warcType.equals("response") &&
              !warc.payload.equals("")) {
              // If this document is a multiple of a thousand, note it in the log
              // and the current documents / second
              if (i % 1000 == 0 &&
                i != 0) {
                logger.info("Processing document: " + i +
                  " (" +
                  ("%.2f" format (i.toDouble /
                    ((System.nanoTime - nanos).toDouble /
                      Timing.Seconds.divisor.toDouble))) + " doc/sec)")
              }
              try {
                processWarcRecord(warc, writer, inputType)
              } catch {
                case e: Throwable =>
                  logger.error("Error while processing warc record: " +
                    warc.warcTrecId + "\n\t" + e + ": " + e.getStackTraceString)
              }
              i = i + 1;
            }
          }

        }
      }
    }

    logger.info("Processed file '" + inputFile.getName + "' -> '"
      + outputFile.getName + "' in: " + Timing.Seconds.format(ns))
  }

  // Given a warc record, processes it using boilerpipe and writes each
  // sentences out to writer
  def processWarcRecord(warc: WarcRecord, writer: PrintWriter, inputType: String) = {

    // piped stores the payload after being passed through boilerpipe
    val piped = try {
      //run boilerPipe only if it is warc
      if (inputType.equals("warc"))
        bp.getText(warc.payload.trim)
      else
        warc.payload
    } catch {
      case e: Throwable =>
        logger.error("Error during boilerpipe extraction. " +
          "Skipping document: " + warc.warcTrecId + "\n\t" +
          e + ": " + e.getStackTraceString)
        ""
    }

    val sentences = nlpSentencer.segmentTexts(piped)

    // iterate over sentences
    var i = 0
    for (s <- sentences) {
      try {
        if (processSentence(s, warc, writer, i))
          i += 1
      } catch {
        case e: Throwable =>
          logger.error("Error while processing sentence " +
            warc.warcTrecId + ":" + i + "\n\t" + e + ": " +
            e.getStackTraceString)
      }
    }
  }

  // Processes a given warc sentence with some filters. If the sentence passes
  // through the filters, it gets written into writer.
  def processSentence(sent: String,
    warc: WarcRecord,
    writer: PrintWriter,
    i: Int) = {
    val sentence = garbager.removeWhitespace(sent)
    if (!garbager.containsHtml(sentence) &&
      !garbager.tooLong(sentence) &&
      !garbager.tooShort(sentence)) {
      writer.println(warc.warcTrecId + "\t" +
        warc.warcUri + "\t" +
        warc.warcDate + "\t" +
        i + "\t" +
        sentence)
      true
    } else {
      false
    }
  }

  // Given a Config object with input files, returns an Iterable over pairs of
  // (inputfile, outputfile), where inputfile corresponds to a File in config,
  // and outputfile is the corresponding output file.
  def getInputOutputFiles(config: Config): Iterable[(File, File)] = {
    import org.apache.commons.io.FileUtils
    import scala.collection.JavaConverters._

    config.inputFiles.flatMap {
      file =>
        // if it's a directory, search subdirectories
        val inputType = config.inputType
        if (file.isDirectory) {
          val files: Iterable[File] =
            //changed warc into inputType so it searches for the correct type
            FileUtils.listFiles(file, Array("gz", inputType), true).asScala

          files.flatMap { inputFile =>
            val subdirectory = inputFile.getParentFile.getPath.drop(file.getParentFile.getPath.length).drop(1)

            // build the output file
            val outputDirectory = config.outputDirectory match {
              case Some(dir) => new File(dir, subdirectory)
              case None => new File(subdirectory)
            }

            // create the file's parent directory if it doesn't exist
            outputDirectory.mkdirs

            val outputFileName = makeOutputFileName(inputFile)
            val outputFile = new File(outputDirectory, outputFileName)

            // if the output file already exists, skip by returning None
            if (outputFile.exists) {
              None
            } else {
              Some(inputFile, outputFile)
            }
          }

        } else {
          // the user input a simple .warc file
          val outputFileName = makeOutputFileName(file)
          val outputFile = config.outputDirectory match {
            case Some(dir) => new File(dir, outputFileName)
            case None => new File(outputFileName)
          }
          Some(file, outputFile)
        }
    }
  }

  // Output filename is the input filename up to and including the first dot
  // with "sentences" as the extension.
  def makeOutputFileName(inputFile: File) = {
    inputFile.getName().takeWhile(_ != '.') + ".sentences"
  }

  def usage {
    "Usage: java -jar <this.jar> <input.warc(.gz)>"
  }

  def openInputStream(file: File): InputStream = {
    if (file.getName endsWith ".gz") {
      // then the user has passed in .warc.gz file
      logger.info("Opening zip file " + file)
      new GZIPInputStream(new FileInputStream(file))
    } else {
      // then the user has passed in .warc file
      logger.info("Opening file " + file)
      new FileInputStream(file)
    }
  }
}
