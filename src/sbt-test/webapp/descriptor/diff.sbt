def diff(a: String, b: String): Unit = {
  try {
    val fa = scala.io.Source.fromFile(a).mkString
    val fb = scala.io.Source.fromFile(b).mkString
    if (fa == fb) {
      ()
    } else {
      sys.error(s"${fa} != ${fb}")
    }
  } catch {
    case e: Exception =>
      sys.error("Caught exception " + e.toString)
  }
}

val diff = inputKey[Unit]("diff")

diff := {
  complete.DefaultParsers.spaceDelimited("<arg>").parsed.toList match {
    case List(a, b) => diff(a, b)
    case _ => throw new Exception("Usage: diff <file> <file>")
  }
}
