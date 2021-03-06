package oxidation

sealed trait ImportSpecifier

object ImportSpecifier {
  case object All extends ImportSpecifier
  final case class Members(members: List[String]) extends ImportSpecifier
}
