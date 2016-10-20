import sbt._

object Resolvers {
  val list = Seq(
    Resolver.mavenLocal,
    Resolver.sonatypeRepo("releases"),
    Resolver.typesafeRepo("releases"),
    Resolver.jcenterRepo,
    Resolver.sbtPluginRepo("sbt-plugin-releases"),
    Resolver.bintrayRepo("mingchuno", "maven")
  )
}
