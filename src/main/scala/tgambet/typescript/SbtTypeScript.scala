package tgambet.typescript

import sbt._
import sbt.Keys._
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import com.typesafe.sbt.web.Import._
import sbt.Configuration
import scala.util.matching.Regex
import com.typesafe.sbt.web.{SbtWeb, LineBasedProblem, CompileProblemsException, CompileProblems}
import xsbti.Severity
import akka.util.Timeout

object TypeScriptKeys {

  val typescript = TaskKey[Seq[File]]("typescript", "Invoke the TypeScript compiler.")

  val modules = SettingKey[Seq[String]]("typescript-modules", "A list of file names to be exported as modules (using TypeScript --out parameter).")

  val targetES5 = SettingKey[Boolean]("typescript-opt-target", "Whether TypeScript should target ECMAScript 5. False by default (ES3).")

  val sourceMap = SettingKey[Boolean]("typescript-opt-sourceMap", "Whether TypeScript should generate corresponding .map files.")

  object ModuleType extends Enumeration { val Amd, CommonJs = Value }
  val moduleType = SettingKey[ModuleType.Value]("typescript-opt-module", "Specify module code generation: 'Commonjs' or 'Amd'")

  val noImplicitAny = SettingKey[Boolean]("typescript-opt-noImplicitAny", "Warn on expressions and declarations with an implied 'any' type.")

  val removeComments = SettingKey[Boolean]("typescript-opt-removeComments", "Do not emit comments to output.")

}


object SbtTypeScript extends AutoPlugin {

  override def requires = MySbtJsTask
  override def trigger = AllRequirements
  val autoImport = TypeScriptKeys
  import autoImport._
  import MySbtJsTask.autoImport.JsTaskKeys._
  import com.typesafe.sbt.web.SbtWeb.autoImport.WebKeys.reporter

  val defaults = Seq(
    modules := Seq.empty,
    targetES5 := false,
    sourceMap := false,
    moduleType := ModuleType.Amd,
    noImplicitAny := false,
    removeComments := false
  )

  val jsTaskSettings = Seq(

    moduleName := typescript.key.toString,

    taskMessage := "TypeScript compiling",

    shellFileName := "tsc.js",

    sourcesDir := {
      val sources = (target in Plugin).value / moduleName.value
      SbtWeb.copyResourceTo(
        to = sources,
        url = getClass.getClassLoader.getResource(moduleName.value + "/tsc.js"),
        cacheDir = streams.value.cacheDirectory / "copy-resource"
      )
      SbtWeb.copyResourceTo(
        to = sources,
        url = getClass.getClassLoader.getResource(moduleName.value + "/lib.d.ts"),
        cacheDir = streams.value.cacheDirectory / "copy-resource"
      )
      sources
    },

    includeFilter := "*.ts",

    excludeFilter := "*.d.ts",

    argumentsGenerator in Assets := {
      (sources: Seq[File], config: Configuration) => { source: File =>
        val args = new ListBuffer[String]
        args += ("--outDir", (resourceManaged in typescript in Assets).value.getAbsolutePath)
        if ((targetES5 in Assets).value) args += ("--target", "ES5")
        if ((sourceMap in Assets).value) args += "--sourcemap"
        if ((noImplicitAny in Assets).value) args += "--noImplicitAny"
        if ((removeComments in Assets).value) args += "--removeComments"
        (moduleType in Assets).value match {
          case ModuleType.Amd => args += ("--module", "amd")
          case ModuleType.CommonJs => args += ("--module", "commonjs")
        }
        val path: Option[String] = source.relativeTo((sourceDirectory in Assets).value).map(_.toString).filter(modules.value.contains)
        if (path.isDefined) args += ("--out", path.get)
        args += source.getAbsolutePath
        args.toSeq
      }
    },

    argumentsGenerator in TestAssets := {
      (sources: Seq[File], config: Configuration) => { source: File =>
        val args = new ListBuffer[String]
        args += ("--outDir", (resourceManaged in typescript in TestAssets).value.getAbsolutePath)
        if ((targetES5 in TestAssets).value) args += ("--target", "ES5")
        if ((sourceMap in TestAssets).value) args += "--sourcemap"
        if ((noImplicitAny in TestAssets).value) args += "--noImplicitAny"
        if ((removeComments in TestAssets).value) args += "--removeComments"
        (moduleType in TestAssets).value match {
          case ModuleType.Amd => args += ("--module", "amd")
          case ModuleType.CommonJs => args += ("--module", "commonjs")
        }
        val path: Option[String] = source.relativeTo((sourceDirectory in TestAssets).value).map(_.toString).filter(modules.value.contains)
        if (path.isDefined) args += ("--out", path.get)
        args += source.getAbsolutePath
        args.toSeq
      }
    },

    onError := { s =>
      val problems = s.split("\n").map{ s =>
        val a = new Regex("""^([^(]+)\((\d+),(\d+)\)\: (.*)$""", "file", "line", "column", "error")
        a.findFirstMatchIn(s) match {
          case Some(m) =>
            val fileName = m.group("file")
            val line = m.group("line")
            val column = m.group("column")
            val error = m.group("error")
            val snippet = "" // No code snippet is provided for now
            Some(new LineBasedProblem(error, Severity.Error, line.toInt, column.toInt, snippet, file(fileName)))
          case None =>
            streams.value.log.warn(s"Unexpected error output: $s")
            None
        }
      }
      CompileProblems.report((reporter in typescript).value, problems.filter(_.isDefined).map(_.get))
    },

    typescript in Assets := MySbtJsTask.myJsTask(typescript, Assets).value,
    typescript in TestAssets := MySbtJsTask.myJsTask(typescript, TestAssets).value,
    resourceGenerators in Assets <+= typescript in Assets,
    resourceGenerators in TestAssets <+= typescript in TestAssets
  )

  val quickHelp = "Examples: tsc hello.ts\n          tsc --out foo.js foo.ts\n          tsc @args.txt"

  val tsc: Command = Command.args("tsc", quickHelp)((s, args) => {
    implicit val timeout = Timeout(5.seconds)
    MySbtJsTask.executeSync(
      shellSource = file(getClass.getClassLoader.getResource("typescript/tsc.js").toString.replace("file:/", "")), // TODO
      args = args,
      error => s.log.error(error),
      output => s.log.info(output),
      state = s
    )
    s
  })

  override def projectSettings = defaults ++ jsTaskSettings ++ Seq(commands += tsc)

}
