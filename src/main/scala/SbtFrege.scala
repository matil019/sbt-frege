import sbt._
import sbt.Keys._

import scala.util.Try

object SbtFregec extends AutoPlugin {

  object autoImport {
    lazy val fregeOptions  = settingKey[Seq[String]]("Extra options for fregec")
    lazy val fregeSource   = settingKey[File]("Frege source directory")
    lazy val fregeTarget   = settingKey[File]("Frege target directory")
    lazy val fregeCompiler = settingKey[String]("Full name of the Frege compiler")
    lazy val fregeLibrary  = settingKey[ModuleID]("Frege library (fregec.jar)")
    lazy val fregeDoc      = taskKey[File]("Generate fregedoc")
  }

  import autoImport._
  import java.io.File

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  def fregec(cp: Seq[sbt.Attributed[File]], fregeSource: File, fregeTarget: File,
             fregeCompiler: String, fregeOptions: Seq[String])
            (fregeSrcs: Set[File]): Set[File] = {

    val cps = cp.map(_.data).mkString(String.valueOf(File.pathSeparatorChar))

    fregeTarget.mkdirs()

    val fregeArgs = Seq(
      fregeCompiler,
      "-j",
      "-fp", cps,
      "-d", fregeTarget.getPath,
      "-sp", fregeSource.getPath,
      "-make"
    ) ++ fregeOptions ++ fregeSrcs.map(_.getPath)

    val forkOptions: ForkOptions = new ForkOptions
    val fork = new Fork("java", None)
    val result = fork(forkOptions, Seq("-cp", cps) ++ fregeArgs)
    if (result != 0) {
      throw new RuntimeException("Frege compilation error")
    } else {
      (PathFinder(fregeTarget) ** "*.java").get.toSet
    }
  }

  def fregedoc(cp: Seq[sbt.Attributed[File]], fregeTarget: File, fregeDoc: File): File = {
    val cps = cp.map(_.data).mkString(String.valueOf(File.pathSeparatorChar))

    fregeDoc.mkdirs()

    def runDoc() = Try {
      val fork = new Fork("java", None)
      val result = fork(
        new ForkOptions,
        "-cp" :: cps :: "frege.tools.Doc" :: "-d" :: fregeDoc.toString :: fregeTarget.toString :: Nil
      )
      if (result != 0) {
        throw new RuntimeException("Fregedoc error")
      } else {
        fregeDoc
      }
    }

    def runMakeDocIndex() = Try {
      val fork = new Fork("java", None)
      val result = fork(
        new ForkOptions,
        "-cp" :: cps :: "frege.tools.MakeDocIndex" :: fregeDoc.toString :: Nil
      )
      if (result != 0) {
        throw new RuntimeException("Fregedoc (MakeDocIndex) error")
      } else {
        fregeDoc
      }
    }

    def copyIndex() = Try {
      IO.copyFile(fregeDoc / "fregedoc.html", fregeDoc / "index.html")
    }

    val run =
      for {
        _ <- runDoc()
        f <- runMakeDocIndex()
        _ <- copyIndex()
      } yield f
    run.get
  }

  def scopedSettings(scope: Configuration, dirName: String) = Seq(
    fregeSource in scope := (sourceDirectory in scope).value / "frege",
    fregeTarget in scope := baseDirectory.value / "target" / "frege" / dirName,
    sourceGenerators in scope += Def.task {
      val cacheDir = streams.value.cacheDirectory / "frege" / dirName
      val cached = FileFunction.cached(
        cacheDir, FilesInfo.lastModified, FilesInfo.exists) {
          fregec((managedClasspath in scope).value ++ (dependencyClasspath in scope).value,
                 (fregeSource in scope).value,
                 (fregeTarget in scope).value,
                 (fregeCompiler in scope).value,
                 (fregeOptions in scope).value)
        }
      cached(((fregeSource in scope).value ** "*.fr").get.toSet).toSeq
    }.taskValue
  )

  override def projectSettings =
    scopedSettings(Compile, "main") ++
    scopedSettings(Test,    "test") ++
    Seq(
      fregeOptions := Seq(),
      fregeDoc in Compile := {
        (sources in Compile).value
        val docdir = baseDirectory.value / "target" / "fregedoc" / "main"
        fregedoc((managedClasspath in Compile).value ++ (dependencyClasspath in Compile).value,
                 (fregeTarget in Compile).value,
                 docdir)
      },
      fregeCompiler := "frege.compiler.Main",
      watchSources := {
        watchSources.value ++
        ((fregeSource in Compile).value ** "*").get ++
        ((fregeSource in Test).value ** "*").get
      },
      fregeLibrary := "org.frege-lang" % "frege" % "3.24.100.1" classifier "jdk8",
      libraryDependencies += fregeLibrary.value
    )

}

/* Adds integration with frege-repl:
 *   * Launch the Frege REPL from sbt with the fregeRepl command
 *   * Access your project's classes and libraries from the Frege REPL
 * See: https://github.com/Frege/frege-repl
 */
object SbtFregeRepl extends AutoPlugin {

  object autoImport {
    // Use a special configuration so as not to pollute the Compile
    // configuration with frege-repl's jar and transitive dependencies.
    lazy val fregeReplConfig    = config("fregeReplConfig").hide
    lazy val fregeReplVersion   = settingKey[String]("Frege REPL version")
    lazy val fregeRepl          = inputKey[Unit]("Run the Frege REPL")
    lazy val fregeReplMainClass = settingKey[String]("Frege REPL main class")
  }

  import autoImport._

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin
  override val projectConfigurations = Seq(fregeReplConfig)

  override def projectSettings = Seq(
    fregeReplVersion := "1.3",
    libraryDependencies += "org.frege-lang" % "frege-repl-core" % fregeReplVersion.value % fregeReplConfig,
    fregeReplMainClass := "frege.repl.FregeRepl",
    fregeRepl := {
      val cp: String = Path.makeString((
        Classpaths.managedJars(fregeReplConfig, classpathTypes.value, update.value) ++
        (fullClasspath in Compile).value
      ).map(_.data))
      val forkOptions = new ForkOptions(connectInput = true, outputStrategy = Some(sbt.StdoutOutput))
      val mainClass: String = fregeReplMainClass.value
      new Fork("java", None).fork(forkOptions, Seq("-cp", cp, mainClass)).exitValue()
    }
  )

}
