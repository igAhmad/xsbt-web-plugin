package com.earldouglas.xwp

import java.util.jar.Manifest

import sbt._
import sbt.Def.taskKey
import sbt.Def.settingKey
import sbt.Keys._
import sbt.FilesInfo.lastModified
import sbt.FilesInfo.exists
import sbt.FileFunction.cached

object WebappPlugin extends AutoPlugin {

  object autoImport {

    sealed trait WebappMount
    case class Servlet(path: String, className: String, async: Boolean) extends WebappMount
    case class Filter(path: String, className: String, async: Boolean) extends WebappMount

    lazy val webappPrepare       = taskKey[Seq[(File, String)]]("prepare webapp contents for packaging")
    lazy val webappPostProcess   = taskKey[File => Unit]("additional task after preparing the webapp")
    lazy val webappWebInfClasses = settingKey[Boolean]("use WEB-INF/classes instead of WEB-INF/lib")
    lazy val webappMounts        = settingKey[Seq[WebappMount]]("servlets/filters for generated WEB-INF/web.xml")

  }

  import autoImport._

  override def requires = plugins.JvmPlugin

  override def projectSettings: Seq[Setting[_]] =
    Seq(
        sourceDirectory in webappPrepare := (sourceDirectory in Compile).value / "webapp"
      , target in webappPrepare          := (target in Compile).value / "webapp"
      , webappPrepare                    := webappPrepareTask.value
      , webappPostProcess                := { _ => () }
      , webappWebInfClasses              := false
      , webappMounts                     := Nil
      , resourceGenerators in Compile    += getWebappMounts
      , Compat.watchSourceSetting
    )

  private def getWebappMounts: Def.Initialize[Task[Seq[java.io.File]]] =
    Def.task {
      if (!webappMounts.value.isEmpty) {
        val file = (target in webappPrepare).value / "WEB-INF" / "web.xml"
        IO.write( file
                , List( "<web-app>"
                      , webappMounts.value.map({
                          case Filter(path, className, async) =>
                            s"""|  <filter>
                                |    <filter-name>${className}</filter-name>
                                |    <filter-class>${className}</filter-class>
                                |    <async-supported>${async}</async-supported>
                                |  </filter>
                                |  <filter-mapping>
                                |    <filter-name>${className}</filter-name>
                                |    <url-pattern>${path}</url-pattern>
                                |  </filter-mapping>""".stripMargin
                          case Servlet(path, className, async) =>
                            s"""|  <servlet>
                                |    <servlet-name>${className}</servlet-name>
                                |    <servlet-class>${className}</servlet-class>
                                |    <async-supported>${async}</async-supported>
                                |  </servlet>
                                |  <servlet-mapping>
                                |    <servlet-name>${className}</servlet-name>
                                |    <url-pattern>${path}</url-pattern>
                                |  </servlet-mapping>""".stripMargin
                        }).mkString("\n")
                      , "</web-app>"
                      ).mkString("\n")
                )
        Seq(file)
      } else {
        Seq.empty
      }
    }

  private def webappPrepareTask: Def.Initialize[Task[Seq[(java.io.File, String)]]] = Def.task {

    def cacheify(name: String, dest: File => Option[File], in: Set[File]): Set[File] =
      Compat.cached(streams.value.cacheDirectory / "xsbt-web-plugin" / name, lastModified, exists)({
        (inChanges, outChanges) =>
          // toss out removed files
          for {
            removed  <- inChanges.removed
            toRemove <- dest(removed)
          } yield IO.delete(toRemove)

          // apply and report changes
          for {
            in  <- inChanges.added ++ inChanges.modified -- inChanges.removed
            out <- dest(in)
            _    = IO.copyFile(in, out)
          } yield out
      }).apply(in)

    val webappSrcDir = (sourceDirectory in webappPrepare).value
    val webappTarget = (target in webappPrepare).value

    val classpath = (fullClasspath in Runtime).value
    val webInfDir = webappTarget / "WEB-INF"
    val webappLibDir = webInfDir / "lib"

    cacheify(
      "webapp",
      { in =>
        for {
          f <- Some(in)
          if !f.isDirectory
          r <- IO.relativizeFile(webappSrcDir, f)
        } yield IO.resolve(webappTarget, r)
      },
      (webappSrcDir ** "*").get.toSet
    )

    val m = (mappings in (Compile, packageBin)).value
    val p = (packagedArtifact in (Compile, packageBin)).value._2 

    if (webappWebInfClasses.value) {
      // copy this project's classes directly to WEB-INF/classes
      cacheify(
        "classes",
        { in =>
          m find {
            case (src, dest) => src == in
          } map { case (src, dest) =>
            webInfDir / "classes" / dest
          }
        },
        (m filter {
          case (src, dest) => !src.isDirectory
        } map { case (src, dest) =>
          src
        }).toSet
      )
    } else {
      // copy this project's classes as a .jar file in WEB-INF/lib
      cacheify(
        "lib-art",
        { in => Some(webappLibDir / in.getName) },
        Set(p)
      )
    }

    // create .jar files for depended-on projects in WEB-INF/lib
    for {
      cpItem    <- classpath.toList
      dir        = cpItem.data
      if dir.isDirectory
      artEntry  <- cpItem.metadata.entries find { e => e.key.label == "artifact" }
      cpArt      = artEntry.value.asInstanceOf[Artifact]
      artifact   = (packagedArtifact in (Compile, packageBin)).value._1
      if cpArt  != artifact
      files      = (dir ** "*").get flatMap { file =>
        if (!file.isDirectory)
          IO.relativize(dir, file) map { p => (file, p) }
        else
          None
      }
      jarFile    = cpArt.name + ".jar"
      _          = IO.jar(files, webappLibDir / jarFile, new Manifest)
    } yield ()

    // copy this project's library dependency .jar files to WEB-INF/lib
    cacheify(
      "lib-deps",
      { in => Some(webappTarget / "WEB-INF" / "lib" / in.getName) },
      classpath.map(_.data).toSet filter { in =>
        !in.isDirectory && in.getName.endsWith(".jar")
      }
    )

    webappPostProcess.value(webappTarget)

    (webappTarget ** "*") pair (Path.relativeTo(webappTarget) | Path.flat)
  }

}
