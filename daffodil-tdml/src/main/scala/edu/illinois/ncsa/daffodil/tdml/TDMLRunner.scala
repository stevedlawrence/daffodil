package edu.illinois.ncsa.daffodil.tdml

/* Copyright (c) 2012-2013 Tresys Technology, LLC. All rights reserved.
 *
 * Developed by: Tresys Technology, LLC
 *               http://www.tresys.com
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal with
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 * 
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimers.
 * 
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimers in the
 *     documentation and/or other materials provided with the distribution.
 * 
 *  3. Neither the names of Tresys Technology, nor the names of its contributors
 *     may be used to endorse or promote products derived from this Software
 *     without specific prior written permission.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
 * SOFTWARE.
 */

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.net.URI
import scala.io.Codec.string2codec
import scala.xml.Node
import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.SAXParseException
import scala.xml.Utility
import scala.xml.parsing.ConstructingParser
import org.xml.sax.InputSource
import com.ibm.icu.charset.CharsetICU
import edu.illinois.ncsa.daffodil.Tak
import edu.illinois.ncsa.daffodil.api.DFDL
import edu.illinois.ncsa.daffodil.api.DataLocation
import edu.illinois.ncsa.daffodil.api.ValidationMode
import edu.illinois.ncsa.daffodil.api.WithDiagnostics
import edu.illinois.ncsa.daffodil.compiler.Compiler
import edu.illinois.ncsa.daffodil.configuration.ConfigurationLoader
import edu.illinois.ncsa.daffodil.dsom.EntityReplacer
import edu.illinois.ncsa.daffodil.dsom.ValidationError
import edu.illinois.ncsa.daffodil.exceptions.Assert
import edu.illinois.ncsa.daffodil.externalvars.Binding
import edu.illinois.ncsa.daffodil.externalvars.ExternalVariablesLoader
import edu.illinois.ncsa.daffodil.processors.DFDLCharCounter
import edu.illinois.ncsa.daffodil.processors.GeneralParseFailure
import edu.illinois.ncsa.daffodil.processors.IterableReadableByteChannel
import edu.illinois.ncsa.daffodil.util.Error
import edu.illinois.ncsa.daffodil.util.LogLevel
import edu.illinois.ncsa.daffodil.util.Logging
import edu.illinois.ncsa.daffodil.util.Misc
import edu.illinois.ncsa.daffodil.util.Misc.bits2Bytes
import edu.illinois.ncsa.daffodil.util.Misc.bytes2Bits
import edu.illinois.ncsa.daffodil.util.Misc.hex2Bits
import edu.illinois.ncsa.daffodil.util.SchemaUtils
import edu.illinois.ncsa.daffodil.util.Timer
import edu.illinois.ncsa.daffodil.xml.DaffodilXMLLoader
import edu.illinois.ncsa.daffodil.xml.XMLUtils
import edu.illinois.ncsa.daffodil.util.Bits
import edu.illinois.ncsa.daffodil.processors.charset.CharsetUtils
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CoderResult
import java.io.InputStream
import edu.illinois.ncsa.daffodil.processors.charset.NonByteSizeCharsetEncoderDecoder
import scala.language.postfixOps

/**
 * Parses and runs tests expressed in IBM's contributed tdml "Test Data Markup Language"
 */

//
// TODO: validate the infoset XML (expected result) against the DFDL Schema, that is using it as an XML Schema
// for the infoset. This would prevent errors where the infoset instance and the schema drift apart under maintenance.
//
// TODO: validate the actual result against the DFDL Schema using it as an XML Schema. 
//
/**
 * TDML test suite runner
 *
 * Keep this independent of Daffodil, so that it can be used to run tests against other DFDL implementations as well.
 * E.g., it should only need an API specified as a collection of Scala traits, and some simple way to inject
 * dependency on one factory to create processors.
 *
 *
 * Use the validateTDMLFile arg to bypass validation of the TDML document itself.
 *
 * This is used for testing whether one can detect validation errors
 * in the DFDL schema.
 *
 * Without this, you can't get to the validation errors, because it
 * rejects the TDML file itself.
 */

class DFDLTestSuite(aNodeFileOrURL: Any,
  validateTDMLFile: Boolean = true,
  val validateDFDLSchemas: Boolean = true)
  extends Logging {

  val errorHandler = new org.xml.sax.ErrorHandler {
    def warning(exception: SAXParseException) = {
      loadingExceptions == exception +: loadingExceptions
      System.err.println("TDMLRunner Warning: " + exception.getMessage())
    }

    def error(exception: SAXParseException) = {
      loadingExceptions = exception :: loadingExceptions
      System.err.println("TDMLRunner Error: " + exception.getMessage())
      isLoadingError = true
    }
    def fatalError(exception: SAXParseException) = {
      loadingExceptions == exception +: loadingExceptions
      System.err.println("TDMLRunner Fatal Error: " + exception.getMessage())
      isLoadingError = true
    }
  }

  var isLoadingError: Boolean = false

  var loadingExceptions: List[Exception] = Nil

  def getLoadingDiagnosticMessages() = {
    val msgs = loadingExceptions.map { _.toString() }.mkString(" ")
    msgs
  }

  /**
   * our loader here accumulates load-time errors here on the
   * test suite object.
   */
  val loader = new DaffodilXMLLoader(errorHandler)
  loader.setValidation(validateTDMLFile)

  /**
   * Detects the encoding of the File for us.
   */
  def determineEncoding(theFile: File): String = {
    val encH = scala.xml.include.sax.EncodingHeuristics
    val is = new java.io.FileInputStream(theFile)
    val bis = new java.io.BufferedInputStream(is)
    val enc = encH.readEncodingFromStream(bis)
    enc
  }

  val (ts, tdmlFile, tsInputSource) = {

    /**
     * We have to use the ConstructingParser in order for Scala to preserve
     * CDATA elements in the dfdl:infoset of the TDML tests.  However,
     * ConstructingParser does not detect encoding and neither does scala.io.Source.
     * We have to use EncodingHeuristics to detect this encoding for us.
     *
     * The other thing to note here is that we still need to perform some sort
     * of validation against the TDML.  We do this by leaving the original
     * loader.loadFile(file) call here.
     */
    val tuple = aNodeFileOrURL match {
      case tsNode: Node => {
        val tempFile = XMLUtils.convertNodeToTempFile(tsNode)
        val enc = determineEncoding(tempFile)
        val input = scala.io.Source.fromURI(tempFile.toURI)(enc)
        val origNode = loader.loadFile(tempFile) // used for validation only
        val newNode = ConstructingParser.fromSource(input, true).document.docElem
        (newNode, null, new InputSource(tempFile.toURI().toASCIIString()))
      }
      case tdmlFile: File => {
        log(LogLevel.Debug, "loading TDML file: %s", tdmlFile)
        val enc = determineEncoding(tdmlFile)
        val input = scala.io.Source.fromURI(tdmlFile.toURI)(enc)
        val origNode = loader.loadFile(tdmlFile) // used for validation only
        val someNode = ConstructingParser.fromSource(input, true).document.docElem
        val res = (someNode, tdmlFile, new InputSource(tdmlFile.toURI().toASCIIString()))
        log(LogLevel.Debug, "done loading TDML file: %s", tdmlFile)
        res
      }
      case tsURI: URI => {
        val f = new File(tsURI)
        val enc = determineEncoding(f)
        val input = scala.io.Source.fromURI(tsURI)(enc)
        val origNode = loader.load(tsURI) // used for validation only
        val someNode = ConstructingParser.fromSource(input, true).document.docElem
        val res = (someNode, null, new InputSource(tsURI.toASCIIString()))
        res
      }
      case _ => Assert.usageError("not a Node, File, or URL")
    }
    tuple
  }

  lazy val isTDMLFileValid = !this.isLoadingError

  var checkAllTopLevel: Boolean = false
  def setCheckAllTopLevel(flag: Boolean) {
    checkAllTopLevel = flag
  }

  val parserTestCases = (ts \ "parserTestCase").map { node => ParserTestCase(node, this) }
  //
  // Note: IBM started this TDML file format. They call an unparser test a "serializer" test.
  // We will use their TDML file names, but in the code here, we call it an UnparserTestCase
  //
  val unparserTestCases = (ts \ "serializerTestCase").map { node => UnparserTestCase(node, this) }
  val testCases: Seq[TestCase] = parserTestCases ++
    unparserTestCases
  val suiteName = (ts \ "@suiteName").text
  val suiteID = (ts \ "@ID").text
  val description = (ts \ "@description").text
  val embeddedSchemas = (ts \ "defineSchema").map { node => DefinedSchema(node, this) }
  val embeddedConfigs = (ts \ "defineConfig").map { node => DefinedConfig(node, this) }

  private val embeddedSchemaGroups = embeddedSchemas.groupBy { _.name }

  embeddedSchemaGroups.foreach {
    case (name, Seq(sch)) => // ok
    case (name, seq) =>
      Assert.usageError("More than one definition for embedded schema " + name)
  }

  def runAllTests(schema: Option[Node] = None) {
    if (isTDMLFileValid)
      testCases.map { _.run(schema) }
    else {
      log(Error("TDML file %s is not valid.", tsInputSource.getSystemId))
    }
  }

  def runPerfTest(testName: String, schema: Option[Node] = None) {
    var bytesProcessed: Long = 0
    var charsProcessed: Long = 0
    Tak.calibrate
    val ns = Timer.getTimeNS(testName, {
      val (by, ch) = runOneTestWithDataVolumes(testName, schema)
      bytesProcessed = by
      charsProcessed = ch
    })
    val takeonsThisRun = ns / Tak.takeons
    val bpns = ((bytesProcessed * 1.0) / ns)
    val kbps = bpns * 1000000
    val callsPerByte = 1 / (Tak.takeons * bpns)
    println("\nKB/sec = " + kbps)
    println("tak call equivalents per byte (takeons/byte) =  " + callsPerByte)
  }

  def runOneTest(testName: String, schema: Option[Node] = None, leakCheck: Boolean = false) {
    if (leakCheck) {
      System.gc()
      Thread.sleep(1) // needed to give tools like jvisualvm ability to "grab on" quickly
    }
    runOneTestWithDataVolumes(testName, schema)
  }

  def runOneTestWithDataVolumes(testName: String, schema: Option[Node] = None): (Long, Long) = {
    if (isTDMLFileValid) {
      val testCase = testCases.find(_.name == testName)
      testCase match {
        case None => throw new Exception("test " + testName + " was not found.")
        case Some(tc) => {
          return tc.run(schema)
        }
      }
    } else {
      log(Error("TDML file %s is not valid.", tsInputSource.getSystemId))
      val msgs = this.loadingExceptions.map { _.toString }.mkString(" ")
      throw new Exception(msgs)
    }
  }

  /**
   * Try a few possibilities to find the model/schema/tdml resources
   *
   * IBM's suites have funny model paths in them. We don't have that file structure,
   * so we look for the schema/model/tdml resources in the working directory, and in the same
   * directory as the tdml file, and some other variations.
   */
  def findTDMLResource(fileName: String): Option[File] = {
    // try it as is. Maybe it will be in the cwd or relative to that or absolute
    val firstTry = new File(fileName)
    if (firstTry.exists()) {
      // it exists, but since validation may use this for defaulting other
      // files, we really do need it to have the directory part.
      val fileWithDir = firstTry.getAbsoluteFile()
      return Some(fileWithDir)
    }
    // see if it can be found relative to the tdml test file, like next to it.
    val sysId = tsInputSource.getSystemId()
    if (sysId != null) {
      val sysFile = new File(URI.create(sysId))
      if (sysFile.exists()) {
        // the system Id of the tdml file was a file.
        val sysPath = sysFile.getParent()
        val resourceFileName = sysPath + File.separator + fileName
        log(LogLevel.Debug, "TDML resource name is: %s", resourceFileName)
        val resourceFile = new File(resourceFileName)
        if (resourceFile.exists()) return Some(resourceFile)
      }
    }
    // try as a classpath resource (allows eclipse to find it)
    // ( also will look in the jar - which is bad. Have to avoid that.)
    val (maybeRes, _) = Misc.getResourceOption(fileName)
    maybeRes.foreach { uri =>
      // could be that we found the file, could be that we
      // got a match in the jar. This should tell them apart.
      if (uri.toURL().getProtocol == "file") {
        val resolvedName = uri.getPath()
        val resFile = new File(resolvedName)
        if (resFile.exists()) return Some(resFile)
      }
    }
    // try ignoring the directory part
    val parts = fileName.split("/")
    if (parts.length > 1) { // if there is one
      val filePart = parts.last
      val secondTry = findTDMLResource(filePart) // recursively
      return secondTry
    }
    None
  }

  def findEmbeddedSchema(modelName: String): Option[Node] = {
    // schemas defined with defineSchema take priority as names.
    val es = embeddedSchemas.find { defSch => defSch.name == modelName }
    es match {
      case Some(defschema) => Some(defschema.xsdSchema)
      case None => None
    }
  }

  def findSchemaFileName(modelName: String) = findTDMLResource(modelName)

  def findEmbeddedConfig(configName: String): Option[DefinedConfig] = {
    val ecfg = embeddedConfigs.find { defCfg => defCfg.name == configName }
    ecfg match {
      case Some(defConfig) => Some(defConfig)
      case None => None
    }
  }

  def findConfigFileName(configName: String) = findTDMLResource(configName)

}

abstract class TestCase(ptc: NodeSeq, val parent: DFDLTestSuite)
  extends Logging {

  def toOpt[T](n: Seq[T]) = {
    n match {
      case Seq() => None
      case Seq(a) => Some(a)
      // ok for it to error if there is more than one in sequence.
    }
  }

  val document = toOpt(ptc \ "document").map { node => new Document(node, this) }
  val infoset = toOpt(ptc \ "infoset").map { node => new Infoset(node, this) }
  val errors = toOpt(ptc \ "errors").map { node => new ExpectedErrors(node, this) }
  val warnings = toOpt(ptc \ "warnings").map { node => new ExpectedWarnings(node, this) }
  val validationErrors = toOpt(ptc \ "validationErrors").map { node => new ExpectedValidationErrors(node, this) }

  val name = (ptc \ "@name").text
  val ptcID = (ptc \ "@ID").text
  val id = name + (if (ptcID != "") "(" + ptcID + ")" else "")
  val root = (ptc \ "@root").text
  val model = (ptc \ "@model").text
  val config = (ptc \ "@config").text
  val description = (ptc \ "@description").text
  val unsupported = (ptc \ "@unsupported").text match {
    case "true" => true
    case "false" => false
    case _ => false
  }
  val validationMode = (ptc \ "@validation").text match {
    case "on" => ValidationMode.Full
    case "limited" => ValidationMode.Limited
    case _ => ValidationMode.Off
  }
  val shouldValidate = validationMode != ValidationMode.Off
  val expectsValidationError = if (validationErrors.isDefined) validationErrors.get.hasDiagnostics else false

  var suppliedSchema: Option[Node] = None

  protected def runProcessor(processor: DFDL.ProcessorFactory,
    data: Option[DFDL.Input],
    nBits: Option[Long],
    infoset: Option[Infoset],
    errors: Option[ExpectedErrors],
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type): Unit

  private def retrieveBindings(cfg: DefinedConfig): Seq[Binding] = {
    val bindings: Seq[Binding] = cfg.externalVariableBindings match {
      case None => Seq.empty
      case Some(bindingsNode) => ExternalVariablesLoader.getVariables(bindingsNode)
    }
    bindings
  }

  def run(schema: Option[Node] = None): (Long, Long) = {
    suppliedSchema = schema
    val sch = schema match {
      case Some(node) => {
        if (model != "") throw new Exception("You supplied a model attribute, and a schema argument. Can't have both.")
        node
      }
      case None => {
        if (model == "") throw new Exception("No model was specified.") // validation of the TDML should prevent writing this.
        val schemaNode = parent.findEmbeddedSchema(model)
        val schemaFileName = parent.findSchemaFileName(model)
        val schemaNodeOrFileName = (schemaNode, schemaFileName) match {
          case (None, None) => throw new Exception("Model '" + model + "' was not passed, found embedded in the TDML file, nor as a schema file.")
          case (Some(_), Some(_)) => throw new Exception("Model '" + model + "' is ambiguous. There is an embedded model with that name, AND a file with that name.")
          case (Some(node), None) => node
          case (None, Some(fn)) => fn
        }
        schemaNodeOrFileName
      }
    }

    val cfg: Option[DefinedConfig] = config match {
      case "" => None
      case configName => {
        val cfgNode = parent.findEmbeddedConfig(configName)
        val cfgFileName = parent.findConfigFileName(configName)
        val optDefinedConfig = (cfgNode, cfgFileName) match {
          case (None, None) => None
          case (Some(_), Some(_)) => throw new Exception("Config '" + config + "' is ambiguous. There is an embedded config with that name, AND a file with that name.")
          case (Some(definedConfig), None) => Some(definedConfig)
          case (None, Some(fn)) => {
            // Read file, convert to definedConfig
            val node = ConfigurationLoader.getConfiguration(fn)
            val definedConfig = DefinedConfig(node, parent)
            Some(definedConfig)
          }
        }
        optDefinedConfig
      }
    }
    val externalVarBindings: Seq[Binding] = cfg match {
      case None => Seq.empty
      case Some(definedConfig) => retrieveBindings(definedConfig)
    }
    val compiler = Compiler(parent.validateDFDLSchemas)
    compiler.setDistinguishedRootNode(root, null)
    compiler.setCheckAllTopLevel(parent.checkAllTopLevel)
    compiler.setExternalDFDLVariables(externalVarBindings)
    val pf = sch match {
      case node: Node => compiler.compile(node)
      case theFile: File => compiler.compile(theFile)
      case _ => Assert.invariantFailed("can only be Node or File")
    }
    val data = document.map { _.data }
    val nBits = document.map { _.nBits }

    runProcessor(pf, data, nBits, infoset, errors, warnings, validationErrors, validationMode)

    val bytesProcessed = IterableReadableByteChannel.getAndResetCalls
    val charsProcessed = DFDLCharCounter.getAndResetCount
    log(LogLevel.Debug, "Bytes processed: " + bytesProcessed)
    log(LogLevel.Debug, "Characters processed: " + charsProcessed)
    (bytesProcessed, charsProcessed)
    // if we get here, the test passed. If we don't get here then some exception was
    // thrown either during the run of the test or during the comparison.
    // log(LogLevel.Debug, "Test %s passed.", id))
  }

  def verifyAllDiagnosticsFound(actual: WithDiagnostics, expectedDiags: Option[ErrorWarningBase]) = {
    val actualDiags = actual.getDiagnostics
    if (actualDiags.length == 0 && expectedDiags.isDefined) {
      throw new Exception("""No diagnostic objects found.""")
    }
    val actualDiagMsgs = actualDiags.map { _.toString }
    val expectedDiagMsgs = expectedDiags.map { _.messages }.getOrElse(Nil)
    // must find each expected warning message within some actual warning message.
    expectedDiagMsgs.foreach {
      expected =>
        {
          val wasFound = actualDiagMsgs.exists {
            actual => actual.toLowerCase.contains(expected.toLowerCase)
          }
          if (!wasFound) {
            throw new Exception("""Did not find diagnostic message """" +
              expected + """" in any of the actual diagnostic messages: """ + "\n" +
              actualDiagMsgs.mkString("\n"))
          }
        }
    }
  }

  def verifyNoValidationErrorsFound(actual: WithDiagnostics) = {
    val actualDiags = actual.getDiagnostics.filter(d => d.isInstanceOf[ValidationError])
    if (actualDiags.length != 0) {
      val actualDiagMsgs = actualDiags.map { _.toString }
      throw new Exception("Validation errors found where none were expected by the test case.\n" +
        actualDiagMsgs.mkString("\n"))
    }
  }

}

case class ParserTestCase(ptc: NodeSeq, parentArg: DFDLTestSuite)
  extends TestCase(ptc, parentArg) {

  def runProcessor(pf: DFDL.ProcessorFactory,
    data: Option[DFDL.Input],
    lengthLimitInBits: Option[Long],
    optInfoset: Option[Infoset],
    optErrors: Option[ExpectedErrors],
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type) = {

    val nBits = lengthLimitInBits.get
    val dataToParse = data.get
    (optInfoset, optErrors) match {
      case (Some(infoset), None) => runParseExpectSuccess(pf, dataToParse, nBits, infoset, warnings, validationErrors, validationMode)
      case (None, Some(errors)) => runParseExpectErrors(pf, dataToParse, nBits, errors, warnings, validationErrors, validationMode)
      case _ => throw new Exception("Invariant broken. Should be Some None, or None Some only.")
    }

  }

  def verifyParseInfoset(actual: DFDL.ParseResult, infoset: Infoset) {
    val trimmed = Utility.trim(actual.result)
    //
    // Attributes on the XML like xsi:type and also namespaces (I think) are 
    // making things fail these comparisons, so we strip all attributes off (since DFDL doesn't 
    // use attributes at all)
    // 
    val actualNoAttrs = XMLUtils.removeAttributes(trimmed)
    // 
    // Would be great to validate the actuals against the DFDL schema, used as
    // an XML schema on the returned infoset XML.
    // Getting this to work is a bigger issue. What with stripping of attributes
    // and that our internal Daffodil XML Catalog has a special treatment of the
    // mapping of the XML Schema URI.
    // etc.
    // 
    // TODO: Fix so we can validate here.
    //

    // Something about the way XML is constructed is different between our infoset
    // results and the ones created by scala directly parsing the TDML test files.
    //
    // This has something to do with values being lists of text nodes and entities
    // and not just simple strings. I.e., if you write: <foo>a&#x5E74;</foo>, that's not
    // an element with a string as its value. It's an element with several text nodes as
    // its values.
    //
    // so we run the expected stuff through the same converters that were used to
    // convert the actual.
    // val expected = XMLUtils.element2ElemTDML(XMLUtils.elem2ElementTDML(infoset.contents)) //val expected = XMLUtils.element2Elem(XMLUtils.elem2Element(infoset.contents))
    val expected = infoset.contents
    // infoset.contents already has attributes removed.
    // however, we call removeAttributes anyway because of the way it collapses
    // multiple strings within a text node.
    val trimmedExpected = XMLUtils.removeAttributes(Utility.trim(expected))

    XMLUtils.compareAndReport(trimmedExpected, actualNoAttrs)
  }

  def runParseExpectErrors(pf: DFDL.ProcessorFactory,
    dataToParse: DFDL.Input,
    lengthLimitInBits: Long,
    errors: ExpectedErrors,
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type) {

    val objectToDiagnose =
      if (pf.isError) pf
      else {
        val processor = pf.onPath("/")
        if (processor.isError) processor
        else {
          val actual = processor.parse(dataToParse, lengthLimitInBits)
          if (actual.isError) actual
          else {
            val loc: DataLocation = actual.resultState.currentLocation

            if (!loc.isAtEnd) {
              actual.addDiagnostic(new GeneralParseFailure("Left over data: " + loc.toString))
              actual
            } else {
              // We did not get an error!!
              // val diags = actual.getDiagnostics().map(_.getMessage()).foldLeft("")(_ + "\n" + _)
              throw new Exception("Expected error. Didn't get one. Actual result was " + actual.briefResult) // if you just assertTrue(actual.canProceed), and it fails, you get NOTHING useful.
            }
          }
          actual
        }
      }

    // check for any test-specified errors
    verifyAllDiagnosticsFound(objectToDiagnose, Some(errors))

    // TODO Implement Warnings
    // check for any test-specified warnings
    // verifyAllDiagnosticsFound(objectToDiagnose, warnings)

  }

  def runParseExpectSuccess(pf: DFDL.ProcessorFactory,
    dataToParse: DFDL.Input,
    lengthLimitInBits: Long,
    infoset: Infoset,
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type) {

    val isError = pf.isError
    val diags = pf.getDiagnostics.map(_.getMessage).mkString("\n")
    if (pf.isError) {
      throw new Exception(diags)
    } else {
      val processor = pf.onPath("/")
      if (processor.isError) {
        val diagObjs = processor.getDiagnostics
        if (diagObjs.length == 1) throw diagObjs(0)
        val diags = diagObjs.map(_.getMessage).mkString("\n")
        throw new Exception(diags)
      }
      processor.setValidationMode(validationMode)
      val actual = processor.parse(dataToParse, lengthLimitInBits)

      if (!actual.canProceed) {
        // Means there was an error, not just warnings.
        val diagObjs = actual.getDiagnostics
        if (diagObjs.length == 1) throw diagObjs(0)
        val diags = actual.getDiagnostics.map(_.getMessage).mkString("\n")
        throw new Exception(diags) // if you just assertTrue(objectToDiagnose.canProceed), and it fails, you get NOTHING useful.
      }

      validationMode match {
        case ValidationMode.Off => // Don't Validate
        case mode => {
          if (actual.isValidationSuccess) {
            // println("Validation Succeeded!") 
          }
        }
      }

      val loc: DataLocation = actual.resultState.currentLocation

      val leftOverException = if (!loc.isAtEnd) {
        val leftOverMsg = "Left over data: " + loc.toString
        println(leftOverMsg)
        Some(new Exception(leftOverMsg))
      } else None

      verifyParseInfoset(actual, infoset)

      (shouldValidate, expectsValidationError) match {
        case (true, true) => verifyAllDiagnosticsFound(actual, validationErrors) // verify all validation errors were found
        case (true, false) => verifyNoValidationErrorsFound(actual) // Verify no validation errors from parser
        case (false, true) => throw new Exception("Test case invalid. Validation is off but the test expects an error.")
        case (false, false) => // Nothing to do here.
      }

      leftOverException.map { throw _ } // if we get here, throw the left over data exception.

      // TODO: Implement Warnings
      // check for any test-specified warnings
      // verifyAllDiagnosticsFound(actual, warnings)

      // if we get here, the test passed. If we don't get here then some exception was
      // thrown either during the run of the test or during the comparison.
    }
  }
}

case class UnparserTestCase(ptc: NodeSeq, parentArg: DFDLTestSuite)
  extends TestCase(ptc, parentArg) {

  def runProcessor(pf: DFDL.ProcessorFactory,
    optData: Option[DFDL.Input],
    optNBits: Option[Long],
    optInfoset: Option[Infoset],
    optErrors: Option[ExpectedErrors],
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type) = {

    val infoset = optInfoset.get

    (optData, optErrors) match {
      case (Some(data), None) => runUnparserExpectSuccess(pf, data, infoset, warnings)
      case (_, Some(errors)) => runUnparserExpectErrors(pf, optData, infoset, errors, warnings)
      case _ => throw new Exception("Invariant broken. Should be Some None, or None Some only.")
    }

  }

  def verifyData(data: DFDL.Input, outStream: java.io.ByteArrayOutputStream) {
    val actualBytes = outStream.toByteArray

    val inbuf = java.nio.ByteBuffer.allocate(1024 * 1024) // TODO: allow override? Detect overrun?
    val readCount = data.read(inbuf)
    data.close()
    if (readCount == -1) {
      // example data was of size 0 (could not read anything). We're not supposed to get any actual data.
      if (actualBytes.length > 0) {
        throw new Exception("Unexpected data was created.")
      }
      return // we're done. Nothing equals nothing.
    }

    Assert.invariant(readCount == inbuf.position())

    // compare expected data to what was output.
    val expectedBytes = inbuf.array().toList.slice(0, readCount)
    if (actualBytes.length != readCount) {
      throw new Exception("output data length " + actualBytes.length + " for " + actualBytes.toList +
        " doesn't match expected value " + readCount + " for " + expectedBytes)
    }

    val pairs = expectedBytes zip actualBytes zip Stream.from(1)
    pairs.foreach {
      case ((expected, actual), index) =>
        if (expected != actual) {
          val msg = "Unparsed data differs at byte %d. Expected 0x%02x. Actual was 0x%02x.".format(index, expected, actual)
          throw new Exception(msg)
        }
    }
  }

  def runUnparserExpectSuccess(pf: DFDL.ProcessorFactory,
    data: DFDL.Input,
    infoset: Infoset,
    warnings: Option[ExpectedWarnings]) {

    val outStream = new java.io.ByteArrayOutputStream()
    val output = java.nio.channels.Channels.newChannel(outStream)
    val node = infoset.contents
    if (pf.isError) {
      val diags = pf.getDiagnostics.map(_.getMessage).mkString("\n")
      throw new Exception(diags)
    }
    val processor = pf.onPath("/")
    if (processor.isError) {
      val diags = processor.getDiagnostics.map(_.getMessage).mkString("\n")
      throw new Exception(diags)
    }
    val actual = processor.unparse(output, node)
    output.close()

    verifyData(data, outStream)

    // TODO: Implement Warnings - check for any test-specified warnings
    // verifyAllDiagnosticsFound(actual, warnings)

  }

  def runUnparserExpectErrors(pf: DFDL.ProcessorFactory,
    optData: Option[DFDL.Input],
    infoset: Infoset,
    errors: ExpectedErrors,
    warnings: Option[ExpectedWarnings]) {

    val outStream = new java.io.ByteArrayOutputStream()
    val output = java.nio.channels.Channels.newChannel(outStream)
    val node = infoset.contents
    if (pf.isError) {
      // check for any test-specified errors
      verifyAllDiagnosticsFound(pf, Some(errors))

      // check for any test-specified warnings
      verifyAllDiagnosticsFound(pf, warnings)
    }
    val processor = pf.onPath("/")
    if (processor.isError) {
      val diags = processor.getDiagnostics.map(_.getMessage).mkString("\n")
      throw new Exception(diags)
    }
    val actual = processor.unparse(output, node)
    output.close()
    val actualBytes = outStream.toByteArray()

    // Verify that some partial output has shown up in the bytes.
    optData.map { data => verifyData(data, outStream) }

    // check for any test-specified errors
    verifyAllDiagnosticsFound(actual, Some(errors))

    // check for any test-specified warnings
    verifyAllDiagnosticsFound(actual, warnings)

  }

}

case class DefinedSchema(xml: Node, parent: DFDLTestSuite) {
  val name = (xml \ "@name").text.toString

  val defineFormats = (xml \ "defineFormat")
  val defaultFormats = (xml \ "format")
  val defineVariables = (xml \ "defineVariable")
  val defineEscapeSchemes = (xml \ "defineEscapeScheme")

  val globalElementDecls = (xml \ "element")
  val globalSimpleTypeDefs = (xml \ "simpleType")
  val globalComplexTypeDefs = (xml \ "complexType")
  val globalGroupDefs = (xml \ "group")
  val globalIncludes = (xml \ "include")
  val globalImports = (xml \ "import")

  val dfdlTopLevels = defineFormats ++ defaultFormats ++ defineVariables ++ defineEscapeSchemes
  val xsdTopLevels = globalImports ++ globalIncludes ++ globalElementDecls ++ globalSimpleTypeDefs ++
    globalComplexTypeDefs ++ globalGroupDefs
  val fileName = parent.ts.attribute(XMLUtils.INT_NS, XMLUtils.FILE_ATTRIBUTE_NAME) match {
    case Some(seqNodes) => seqNodes.toString
    case None => ""
  }
  val xsdSchema = SchemaUtils.dfdlTestSchema(dfdlTopLevels, xsdTopLevels, fileName)
}

case class DefinedConfig(xml: Node, parent: DFDLTestSuite) {
  val name = (xml \ "@name").text.toString
  val externalVariableBindings = (xml \ "externalVariableBindings").headOption

  // Add additional compiler tunable variables here

  val fileName = parent.ts.attribute(XMLUtils.INT_NS, XMLUtils.FILE_ATTRIBUTE_NAME) match {
    case Some(seqNodes) => seqNodes.toString
    case None => ""
  }
}

sealed abstract class DocumentContentType
case object ContentTypeText extends DocumentContentType
case object ContentTypeByte extends DocumentContentType
case object ContentTypeBits extends DocumentContentType
case object ContentTypeFile extends DocumentContentType
// TODO: add capability to specify character set encoding into which text is to be converted (all UTF-8 currently)

sealed abstract class BitOrderType
case object LSBFirst extends BitOrderType
case object MSBFirst extends BitOrderType

sealed abstract class ByteOrderType
case object RTL extends ByteOrderType
case object LTR extends ByteOrderType

case class Document(d: NodeSeq, parent: TestCase) {
  lazy val documentExplicitBitOrder = (d \ "@bitOrder").toString match {
    case "LSBFirst" => Some(LSBFirst)
    case "MSBFirst" => Some(MSBFirst)
    case "" => None
    case _ => Assert.invariantFailed("invalid bit order.")
  }

  lazy val nDocumentParts = dataDocumentParts.length

  lazy val documentBitOrder: BitOrderType = {
    this.documentExplicitBitOrder match {
      case Some(order) => order
      case None => {
        // analyze the child parts 
        val groups = dataDocumentParts.groupBy(_.explicitBitOrder).map {
          case (key, seq) => (key, seq.length)
        }
        if (groups.get(Some(MSBFirst)) == Some(nDocumentParts)) MSBFirst // all are msb first
        else if (groups.get(Some(LSBFirst)) == Some(nDocumentParts)) LSBFirst // all are lsb first
        else if (groups.get(None) == Some(nDocumentParts)) MSBFirst // everything is silent on bit order.
        else {
          // Some mixture of explicit and non-explicit bitOrder
          Assert.usageError(
            "Must specify bitOrder on document element when parts have a mixture of bit orders.")
        }
      }
    }
  }

  val Seq(<document>{ children @ _* }</document>) = d

  val actualDocumentPartElementChildren = children.toList.flatMap {
    child =>
      child match {
        case <documentPart>{ _* }</documentPart> => {
          List((child \ "@type").toString match {
            case "text" => new TextDocumentPart(child, this)
            case "byte" => new ByteDocumentPart(child, this)
            case "bits" => new BitsDocumentPart(child, this)
            case "file" => new FileDocumentPart(child, this)
            case _ => Assert.invariantFailed("invalid content type.")

          })
        }
        case _ => Nil
      }
  }

  // check that document element either contains text content directly with no other documentPart children, 
  // or it contains ONLY documentPart children (and whitespace around them).
  //
  if (actualDocumentPartElementChildren.length > 0) {
    children.foreach { child =>
      child match {
        case <documentPart>{ _* }</documentPart> => // ok
        case scala.xml.Text(s) if (s.matches("""\s+""")) => // whitespace text nodes ok
        case scala.xml.Comment(_) => // ok
        case x => Assert.usageError("Illegal TDML data document content '" + x + "'")
      }
    }
  }

  lazy val unCheckedDocumentParts: Seq[DocumentPart] = {
    if (actualDocumentPartElementChildren.length > 0) actualDocumentPartElementChildren
    else List(new TextDocumentPart(<documentPart type="text">{ children }</documentPart>, this))
  }
  lazy val dataDocumentParts = {
    val dps = unCheckedDocumentParts.collect { case dp: DataDocumentPart => dp }
    dps
  }

  lazy val fileParts = {
    val fps = unCheckedDocumentParts.collect { case fp: FileDocumentPart => fp }
    Assert.usage(fps.length == 0 ||
      (fps.length == 1 && dataDocumentParts.length == 0),
      "There can be only one documentPart of type file, and it must be the only documentPart.")
    fps
  }

  lazy val documentParts = {
    checkForBadBitOrderTransitions(dataDocumentParts)
    dataDocumentParts ++ fileParts
  }

  /**
   * A method because it is easier to unit test it
   */
  def checkForBadBitOrderTransitions(dps: Seq[DataDocumentPart]) {
    if (dps.length <= 1) return
    // these are the total lengths BEFORE the component
    val lengths = dps.map { _.lengthInBits }
    val cumulativeDocumentPartLengthsInBits = lengths.scanLeft(0) { case (sum, num) => sum + num }
    val docPartBitOrders = dps.map { _.partBitOrder }
    val transitions = docPartBitOrders zip docPartBitOrders.tail zip cumulativeDocumentPartLengthsInBits.tail zip dps
    transitions.foreach {
      case (((bitOrderPrior, bitOrderHere), cumulativeLength), docPart) => {
        // println("transition " + bitOrderPrior + " " + bitOrderHere + " " + cumulativeLength)
        Assert.usage(
          (bitOrderPrior == bitOrderHere) || ((cumulativeLength % 8) == 0),
          "bitOrder can only change on a byte boundary.")
      }
    }
  }

  /**
   * When data is coming from the TDML file as small test data in
   * DataDocumentParts, then
   * Due to alignment, and bits-granularity issues, everything is lowered into
   * bits first, and then concatenated, and then converted back into bytes
   *
   * These are all lazy val, since if data is coming from a file these aren't
   * needed at all.
   */
  lazy val documentBits = {
    val nFragBits = (nBits.toInt % 8)
    val nAddOnBits = if (nFragBits == 0) 0 else 8 - nFragBits
    val addOnBits = "0" * nAddOnBits
    val bitsFromParts = dataDocumentParts.map { _.contentAsBits }
    val allPartsBits = documentBitOrder match {
      case MSBFirst => bitsFromParts.flatten
      case LSBFirst => {
        val x = bitsFromParts.map { _.map { _.reverse } }
        val rtlBits = x.flatten.mkString.reverse
        val ltrBits = rtlBits.reverse.sliding(8, 8).map { _.reverse }.toList
        ltrBits
      }
    }
    val allBits = allPartsBits.mkString.sliding(8, 8).toList
    if (allBits == Nil) Nil
    else {
      val lastByte = this.documentBitOrder match {
        case MSBFirst => allBits.last + addOnBits
        case LSBFirst => addOnBits + allBits.last
      }
      val res = allBits.dropRight(1) :+ lastByte
      res
    }
  }

  lazy val nBits: Long = documentParts.map { _.nBits } sum

  lazy val documentBytes = bits2Bytes(documentBits)

  /**
   * this 'data' is the kind our parser's parse method expects.
   * Note: this is def data so that the input is re-read every time.
   * Needed if you run the same test over and over.
   */
  def data = {
    if (isDPFile) {
      // direct I/O to the file. No 'bits' lowering involved. 
      val dp = documentParts(0).asInstanceOf[FileDocumentPart]
      val input = dp.fileDataInput
      input
    } else {
      // assemble the input from the various pieces, having lowered
      // everything to bits.
      val bytes = documentBytes.toArray
      println("data size is " + bytes.length)
      val inputStream = new java.io.ByteArrayInputStream(bytes);
      val rbc = java.nio.channels.Channels.newChannel(inputStream);
      rbc.asInstanceOf[DFDL.Input]
    }
  }

  /**
   * data coming from a file?
   */
  val isDPFile = {
    val res = documentParts.length > 0 &&
      documentParts(0).isInstanceOf[FileDocumentPart]
    if (res) {
      Assert.usage(documentParts.length == 1, "There can be only one documentPart of type file, and it must be the only documentPart.")
    }
    res
  }

}

class TextDocumentPart(part: Node, parent: Document) extends DataDocumentPart(part, parent) {

  lazy val encoder = {
    if (encodingName.toUpperCase == "US-ASCII-7-BIT-PACKED")
      Assert.usage(partBitOrder == LSBFirst, "encoding US-ASCII-7-BIT-PACKED requires bitOrder='LSBFirst'")
    CharsetUtils.getCharset(encodingName).newEncoder()
  }

  lazy val textContentWithoutEntities = {
    if (replaceDFDLEntities) {
      try { EntityReplacer { _.replaceAll(partRawContent) } }
      catch { case (e: Exception) => Assert.abort(e.getMessage()) }
    } else partRawContent
  }

  /**
   * Result is sequence of strings, each string representing a byte or
   * partial byte using '1' and '0' characters for the bits.
   */
  def encodeUtf8ToBits(s: String): Seq[String] = {
    // Fails here if we use getBytes("UTF-8") because that uses the utf-8 encoder,
    // and that will fail on things like unpaired surrogate characters that we allow
    // in our data and our infoset.
    // So instead we must do our own UTF-8-like encoding of the data
    // so that we can put in codepoints we want. 
    val bytes = UTF8Encoder.utf8LikeEncode(textContentWithoutEntities).toArray
    val res = bytes.map { b => (b & 0xFF).toBinaryString.reverse.padTo(8, '0').reverse }.toList
    res
  }

  def encodeWith7BitEncoder(s: String): Seq[String] = {
    val bb = ByteBuffer.allocate(4 * s.length)
    val cb = CharBuffer.wrap(s)
    val coderResult = encoder.encode(cb, bb, true)
    Assert.invariant(coderResult == CoderResult.UNDERFLOW)
    bb.flip()
    val res = (0 to bb.limit() - 1).map { bb.get(_) }
    val bitsAsString = bytes2Bits(res.toArray)
    val enc = encoder.asInstanceOf[NonByteSizeCharsetEncoderDecoder]
    val nBits = s.length * enc.widthOfACodeUnit
    val bitStrings = res.map { b => (b & 0xFF).toBinaryString.reverse.padTo(8, '0').reverse }.toList
    val allBits = bitStrings.reverse.mkString.takeRight(nBits)
    val sevenBitChunks = allBits.reverse.sliding(7, 7).map { _.reverse }.toList
    sevenBitChunks
  }

  def encodeWith8BitEncoder(s: String): Seq[String] = {
    val bb = ByteBuffer.allocate(4 * s.length)
    val cb = CharBuffer.wrap(s)
    val coderResult = encoder.encode(cb, bb, true)
    Assert.invariant(coderResult == CoderResult.UNDERFLOW)
    bb.flip()
    val res = (0 to bb.limit() - 1).map { bb.get(_) }
    val bitsAsString = bytes2Bits(res.toArray)
    val nBits = bb.limit() * 8
    val bitStrings = res.map { b => (b & 0xFF).toBinaryString.reverse.padTo(8, '0').reverse }.toList
    bitStrings
  }

  lazy val dataBits = {
    val bytesAsStrings =
      if (encoder.charset.name.toLowerCase == "utf-8")
        encodeUtf8ToBits(textContentWithoutEntities)
      else if (encodingName.toUpperCase == "US-ASCII-7-BIT-PACKED")
        encodeWith7BitEncoder(textContentWithoutEntities)
      else encodeWith8BitEncoder(textContentWithoutEntities)
    bytesAsStrings
  }
}

class ByteDocumentPart(part: Node, parent: Document) extends DataDocumentPart(part, parent) {
  val validHexDigits = "0123456789abcdefABCDEF"

  lazy val dataBits = {
    val hexBytes = partByteOrder match {
      case LTR => {
        val ltrDigits = hexDigits.sliding(2, 2).toList
        ltrDigits
      }
      case RTL => {
        val rtlDigits = hexDigits.reverse.sliding(2, 2).toList.map { _.reverse }
        rtlDigits
      }
    }
    val bits = hexBytes.map { hex2Bits(_) }
    bits
  }

  // Note: anything that is not a valid hex digit (or binary digit for binary) is simply skipped
  // TODO: we should check for whitespace and other characters we want to allow, and verify them.
  // TODO: Or better, validate this in the XML Schema for tdml via a pattern facet
  // TODO: Consider whether to support a comment syntax. When showing data examples this may be useful.
  //
  lazy val hexDigits = partRawContent.flatMap { ch => if (validHexDigits.contains(ch)) List(ch) else Nil }

}
class BitsDocumentPart(part: Node, parent: Document) extends DataDocumentPart(part, parent) {
  // val validBinaryDigits = "01"

  // lazy val bitContentToBytes = bits2Bytes(bitDigits).toList

  lazy val bitDigits = {
    val res = partRawContent.split("[^01]").mkString
    res
  }

  lazy val dataBits = partByteOrder match {
    case LTR => {
      val ltrBigits = bitDigits.sliding(8, 8).toList
      ltrBigits
    }
    case RTL => {
      val rtlBigits =
        bitDigits.reverse.sliding(8, 8).toList.map { _.reverse }
      rtlBigits
    }
  }
}

class FileDocumentPart(part: Node, parent: Document) extends DocumentPart(part, parent) {

  override lazy val nBits = -1L // signifies we do not know how many.

  lazy val fileDataInput = {
    val maybeFile = parent.parent.parent.findTDMLResource(partRawContent.trim())
    val file = maybeFile.getOrElse(throw new FileNotFoundException("TDMLRunner: data file '" + partRawContent + "' was not found"))
    println("file size is " + file.length())
    val fis = new FileInputStream(file)
    val rbc = fis.getChannel()
    rbc.asInstanceOf[DFDL.Input]
  }

}

/**
 * Base class for all document parts that contain data directly expressed in the XML
 */
sealed abstract class DataDocumentPart(part: Node, parent: Document)
  extends DocumentPart(part, parent) {

  def dataBits: Seq[String]

  lazy val lengthInBits = dataBits.map { _.length } sum
  override lazy val nBits: Long = lengthInBits

  lazy val contentAsBits = dataBits

}

/**
 * Base class for all document parts
 */
sealed abstract class DocumentPart(part: Node, parent: Document) {

  def nBits: Long

  lazy val explicitBitOrder: Option[BitOrderType] = {
    val bitOrd = (part \ "@bitOrder").toString match {

      case "LSBFirst" => Some(LSBFirst)
      case "MSBFirst" => Some(MSBFirst)
      case "" => None
      case _ => Assert.invariantFailed("invalid bit order.")
    }
    Assert.usage(!isInstanceOf[FileDocumentPart],
      "bitOrder may not be specified on document parts of type 'file'")
    bitOrd
  }

  lazy val partBitOrder = explicitBitOrder.getOrElse(parent.documentBitOrder)

  lazy val partByteOrder = {
    val bo = (part \ "@byteOrder").toString match {
      case "RTL" => {
        Assert.usage(partBitOrder == LSBFirst, "byteOrder RTL can only be used with bitOrder LSBFirst")
        RTL
      }
      case "LTR" => LTR
      case "" => LTR
      case _ => Assert.invariantFailed("invalid byte order.")
    }
    Assert.usage(this.isInstanceOf[ByteDocumentPart] || this.isInstanceOf[BitsDocumentPart],
      "byteOrder many only be specified for document parts of type 'byte' or 'bits'")
    bo
  }

  lazy val partRawContent = part.child.text

  lazy val replaceDFDLEntities: Boolean = {
    val res = (part \ "@replaceDFDLEntities")
    if (res.length == 0) { false }
    else {
      Assert.usage(this.isInstanceOf[TextDocumentPart])
      res(0).toString().toBoolean
    }
  }

  lazy val encodingName: String = {
    val res = (part \ "@encoding").text
    if (res.length == 0) { "utf-8" }
    else {
      Assert.usage(this.isInstanceOf[TextDocumentPart])
      res
    }
  }

}

case class Infoset(i: NodeSeq, parent: TestCase) {
  lazy val Seq(dfdlInfoset) = (i \ "dfdlInfoset").map { node => new DFDLInfoset(Utility.trim(node), this) }
  lazy val contents = dfdlInfoset.contents
}

case class DFDLInfoset(di: Node, parent: Infoset) {
  lazy val Seq(contents) = {
    Assert.usage(di.child.size == 1, "dfdlInfoset element must contain a single root element")

    val c = di.child(0)
    val expected = Utility.trim(c) // must be exactly one root element in here.
    val expectedNoAttrs = XMLUtils.removeAttributes(expected)
    //
    // Let's validate the expected content against the schema
    // Just to be sure they don't drift.
    //
    //    val ptc = parent.parent
    //    val schemaNode = ptc.findModel(ptc.model)
    //
    // This is causing trouble, with the stripped attributes, etc.
    // TODO: Fix so we can validate these expected results against
    // the DFDL schema used as a XSD for the expected infoset XML.
    //
    expectedNoAttrs
  }
}

abstract class ErrorWarningBase(n: NodeSeq, parent: TestCase) {
  lazy val matchAttrib = (n \ "@match").text
  protected def diagnosticNodes: Seq[Node]
  lazy val messages = diagnosticNodes.map { _.text }

  def hasDiagnostics: Boolean = diagnosticNodes.length > 0
}

case class ExpectedErrors(node: NodeSeq, parent: TestCase)
  extends ErrorWarningBase(node, parent) {

  val diagnosticNodes = node \\ "error"

}

case class ExpectedWarnings(node: NodeSeq, parent: TestCase)
  extends ErrorWarningBase(node, parent) {

  val diagnosticNodes = node \\ "warning"

}

case class ExpectedValidationErrors(node: NodeSeq, parent: TestCase)
  extends ErrorWarningBase(node, parent) {

  val diagnosticNodes = node \\ "error"

}

object UTF8Encoder {
  def utf8LikeEncode(s: String): Seq[Byte] = {
    // 
    // Scala/Java strings represent characters above 0xFFFF as a surrogate pair
    // of two codepoints. 
    //
    // We want to handle both properly match surrogate pairs, and isolated surrogate characters.
    // That means if we see an isolated low (second) surrogate character, we have to know 
    // whether it was preceded by a high surrogate or not.
    // 
    // For every 16-bit code point, do do this right we need to potentially also see the previous
    // or next codepoint.
    //
    val bytes = XMLUtils.walkUnicodeString(s)(utf8LikeEncoding).flatten
    // val bytes = tuples.flatMap { case ((prevcp, cp), nextcp) => utf8LikeEncoding(prevcp, cp, nextcp) }
    bytes
  }

  def byteList(args: Int*) = args.map { _.toByte }

  /**
   * Encode in the style of utf-8 (see wikipedia article on utf-8)
   *
   * Variation is that we accept some things that a conventional utf-8 encoder
   * rejects. Examples are illegal codepoints such as isolated Unicode surrogates
   * (not making up a surrogate pair).
   *
   * We also assume we're being handed surrogate pairs for any of the
   * 4-byte character representations.
   *
   */

  def utf8LikeEncoding(prev: Char, c: Char, next: Char): Seq[Byte] = {
    // handles 16-bit codepoints only
    Assert.usage(prev <= 0xFFFF)
    Assert.usage(c <= 0xFFFF)
    Assert.usage(next <= 0xFFFF)

    val i = c.toInt
    val byte1 = ((i >> 8) & 0xFF)
    val byte2 = (i & 0xFF)

    def threeByteEncode() = {
      val low6 = byte2 & 0x3F
      val mid6 = ((byte1 & 0x0F) << 2) | (byte2 >> 6)
      val high4 = byte1 >> 4
      byteList(high4 | 0xE0, mid6 | 0x80, low6 | 0x80)
    }

    /**
     * create 4-byte utf-8 encoding from surrogate pair found
     * in a scala string.
     */
    def fourByteEncode(leadingSurrogate: Char, trailingSurrogate: Char) = {
      val h = leadingSurrogate.toInt // aka 'h for high surrogate'
      val l = trailingSurrogate.toInt // aka 'l for low surrogate'
      val cp = 0x10000 + ((h - 0xD800) * 0x400) + (l - 0xDC00)
      val byte1 = (cp >> 24) & 0xFF
      val byte2 = (cp >> 16) & 0xFF
      val byte3 = (cp >> 8) & 0xFF
      val byte4 = cp & 0xFF
      val low6 = byte4 & 0x3F
      val midlow6 = ((byte3 & 0x0F) << 2) | (byte4 >> 6)
      val midhig6 = ((byte2 & 0x03) << 4) | byte3 >> 4
      val high3 = byte2 >> 2
      byteList(high3 | 0xF0, midhig6 | 0x80, midlow6 | 0x80, low6 | 0x80)
    }

    val res = i match {
      case _ if (i <= 0x7F) => byteList(byte2)
      case _ if (i <= 0x7FF) => {
        val low6 = byte2 & 0x3F
        val high5 = ((byte1 & 0x07) << 2) | (byte2 >> 6)
        byteList(high5 | 0xC0, low6 | 0x80)
      }
      case _ if (XMLUtils.isLeadingSurrogate(c)) => {
        // High (initial) Surrogate character case.
        if (XMLUtils.isTrailingSurrogate(next)) {
          // Next codepoint is a low surrogate.
          // We need to create a 4-byte representation from the
          // two surrogate characters.
          fourByteEncode(c, next)
        } else {
          // isolated high surrogate codepoint case.
          threeByteEncode()
        }
      }
      case _ if (XMLUtils.isTrailingSurrogate(c)) => {
        // Low (subsequent) Surrogate character case.
        if (XMLUtils.isLeadingSurrogate(prev)) {
          // Previous codepoint was a high surrogate. 
          // This codepoint was handled as part of converting the
          // surrogate pair.
          // so we output no bytes at all.
          List()
        } else {
          // Isolated low-surrogate codepoint case.
          threeByteEncode()
        }

      }
      case _ if (i <= 0xFFFF) => {
        threeByteEncode()
      }

      case _ => Assert.invariantFailed("char code out of range.")
    }
    res
  }

}
