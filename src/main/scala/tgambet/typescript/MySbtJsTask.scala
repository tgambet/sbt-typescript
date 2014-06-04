package tgambet.typescript

import sbt._
import sbt.Keys._

import com.typesafe.sbt.web.SbtWeb
import com.typesafe.sbt.web.SbtWeb._
import com.typesafe.jse.{Rhino, Node, CommonNode, Engine}
import com.typesafe.jse.Engine.JsExecutionResult

import akka.util.Timeout
import akka.actor.ActorRef

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Await, Future}
import scala.concurrent.duration._

import sbt.Task
import sbt.Configuration
import scala.util.Try

class JsTaskFailure(m: String) extends RuntimeException(m)

object JsTaskImport {

  object JsTaskKeys {

    val sourcesDir = TaskKey[File]("js-task-sourcesDir")
    val shellFileName = SettingKey[String]("js-task-shellfile")
    val taskMessage = SettingKey[String]("js-task-message")
    val argumentsGenerator = SettingKey[(Seq[File], Configuration) => (File => Seq[String])]("js-task-arguments-generator")
    val timeoutPerSource = SettingKey[FiniteDuration]("js-task-timeout-per-source", "The maximum number of seconds to wait per source file processed by the JS task.")
    val onOutput = TaskKey[String => Unit]("js-task-on-output")
    val onError = TaskKey[String => Unit]("js-task-on-error")

  }

}

object MySbtJsTask extends AutoPlugin {

  override def requires = MySbtJsEngine
  override def trigger = AllRequirements
  val autoImport = JsTaskImport
  import autoImport.JsTaskKeys._
  import SbtWeb.autoImport._
  import SbtWeb.autoImport.WebKeys._
  import JsEngineImport.JsEngineKeys._

  override def projectSettings = Seq(
    resourceManaged := webTarget.value / moduleName.value / "main",
    timeoutPerSource := 30.seconds,
    onError := { s => streams.value.log.error(s) },
    onOutput := { s => streams.value.log.info(s) },
    managedResourceDirectories in Assets += resourceManaged.value,
    managedResourceDirectories in TestAssets += resourceManaged.value
  )

  def myJsTask(task: TaskKey[Seq[File]], config: Configuration): Def.Initialize[Task[Seq[File]]] = Def.task {

      val engineProps = MySbtJsEngine.engineTypeToProps(EngineType.NodeLike)
      val sources: Seq[File] =
        ((unmanagedSources in config).value ** ((includeFilter in task in config).value -- (excludeFilter in task in config).value)).get
          .filter(!_.isDirectory)

      val argsGenerator: File => Seq[String] = (argumentsGenerator in task in config).value.apply(sources, config)
      val onOut = (onOutput in config).value
      val onErr = (onError in config).value

      val modifiedSources: Seq[File] = sources
      val maxTaskTime = (timeoutPerSource in task in config).value * modifiedSources.size
      implicit val timeout = Timeout(maxTaskTime)

      if (modifiedSources.size > 0) {

        streams.value.log.info(s"${(taskMessage in task in config).value} on ${modifiedSources.size} source(s)")

        val pendingResults: Future[Seq[File]] = {
          withActorRefFactory(state.value, this.getClass.getName) { arf =>
            val engine = arf.actorOf(engineProps)
            val sourceFileMappings = modifiedSources.pair(relativeTo((unmanagedSourceDirectories in config).value))
            import scala.concurrent.ExecutionContext.Implicits.global

            val shellSource = sourcesDir.value / (shellFileName in task in config).value

            Future.sequence(sourceFileMappings.map { case (script, relativePath) =>
              executeJs(
                engine = engine,
                shellSource = shellSource,
                args = argsGenerator(script),
                error => onErr(error),
                output => onOut(output)
              ) map { result =>
                script
              }
            })
          }
        }
        Await.result(pendingResults, maxTaskTime)
      } else {
        Seq.empty[File]
      }
  }

  private def executeJs(
      engine: ActorRef,
      shellSource: File,
      args: Seq[String],
      stderrSink: String => Unit,
      stdoutSink: String => Unit
    )(implicit timeout: Timeout): Future[JsExecutionResult] = {

    import akka.pattern.ask
    import ExecutionContext.Implicits.global

    (engine ? Engine.ExecuteJs(
      source = shellSource,
      args = args.to[immutable.Seq],
      timeout = timeout.duration
    )).mapTo[JsExecutionResult].map { result =>

      // The engine should know its own encoding, or at least it should be specified at creation.
      val outputOpt = {
        val s = new String(result.output.toArray, "UTF-8")
        if (s == "") None else Some(s)
      }
      val errorOpt = {
        val s = new String(result.error.toArray, "UTF-8")
        if (s == "") None else Some(s)
      }
      errorOpt.map(stderrSink)
      outputOpt.map(stdoutSink)
      if (result.exitValue != 0)
        throw new JsTaskFailure(errorOpt.getOrElse(s"An error occurred while executing JavaScript ($shellSource)"))

      result
    }
  }

  def executeSync(
    shellSource: File,
    args: Seq[String],
    onError: String => Unit,
    onOutput: String => Unit,
    state: State)(implicit timeout: Timeout): JsExecutionResult = {

    import akka.pattern.ask
    import ExecutionContext.Implicits.global
    val engineProps = MySbtJsEngine.engineTypeToProps(EngineType.NodeLike)

    val res = withActorRefFactory(state, this.getClass.getName) { arf =>
      val engine = arf.actorOf(engineProps)
      (engine ? Engine.ExecuteJs(
        source = shellSource,
        args = args.to[immutable.Seq],
        timeout = timeout.duration
      )).mapTo[JsExecutionResult].map { result =>
        val outputOpt = {
          val s = new String(result.output.toArray, "UTF-8")
          if (s == "") None else Some(s)
        }
        val errorOpt = {
          val s = new String(result.error.toArray, "UTF-8")
          if (s == "") None else Some(s)
        }
        errorOpt.map(onError)
        outputOpt.map(onOutput)

        result
      }
    }

    Await.result(res, timeout.duration)
  }
}

object JsEngineImport {

  object JsEngineKeys {

    object EngineType extends Enumeration {
      val NodeLike,
      RhinoLike,
      CommonJS = Value
    }

    val command = SettingKey[Option[File]]("jse-command", "An optional path to the command used to invoke the engine.")
    val engineType = SettingKey[EngineType.Value]("jse-engine-type", "The type of engine to use.")
  }

}

object MySbtJsEngine extends AutoPlugin {

  override def requires = SbtWeb
  override def trigger = AllRequirements
  val autoImport = JsEngineImport
  import autoImport.JsEngineKeys._

  def engineTypeToProps(engineType: EngineType.Value) = {
    engineType match {
      case EngineType.CommonJS => CommonNode.props(None, stdEnvironment = Map.empty)
      case EngineType.NodeLike => Node.props(None, stdEnvironment = Map.empty)
      case EngineType.RhinoLike => Rhino.props()
    }
  }

  private val defaultEngineType = EngineType.CommonJS

  override def projectSettings: Seq[Setting[_]] = Seq(
    engineType := sys.props.get("sbt.jse.engineType").fold(defaultEngineType)(engineTypeStr =>
      Try(EngineType.withName(engineTypeStr)).getOrElse {
        println(s"Unknown engine type $engineTypeStr for sbt.jse.engineType. Resorting back to the default of $defaultEngineType.")
        defaultEngineType
      }),
    command := sys.props.get("sbt.jse.command").map(file)
  )

}
