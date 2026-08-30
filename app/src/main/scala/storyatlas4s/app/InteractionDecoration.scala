package storyatlas4s.app

import storymodel4s.core.Address

/** The independently validated renderer face to which an interaction target belongs. */
private[app] enum InteractionSurface:
  case Atlas
  case Codex

  def label: String = this match
    case Atlas => "Atlas"
    case Codex => "Codex"

/** A closed failure vocabulary for semantic interaction decoration and renderer closure. */
private[app] enum InteractionError:
  case MissingPlacement(address: Address)
  case MissingPlacementMemberTarget(address: Address, member: String)
  case MissingProxyTarget(original: Address, visible: Address)
  case InvalidProxy(original: Address, visible: Address)
  case MissingNavigationTarget(surface: InteractionSurface, name: String)
  case MissingRenderedTarget(surface: InteractionSurface, name: String)
  case PhantomRenderedTarget(surface: InteractionSurface, name: String)
  case DuplicateExpectedTarget(surface: InteractionSurface, name: String, count: Int)
  case DuplicateRenderedTarget(surface: InteractionSurface, name: String, count: Int)
  case TargetIdentityMismatch(
      surface: InteractionSurface,
      name: String,
      expected: Address,
      actual: Address
  )

  def message: String = this match
    case MissingPlacement(address) =>
      s"Compiler omitted placement for ${address.render}"
    case MissingPlacementMemberTarget(address, member) =>
      s"${address.render} has direct placement member $member without a renderer target"
    case MissingProxyTarget(original, visible) =>
      s"${original.render} has proxy placement via ${visible.render} without a renderer target"
    case InvalidProxy(original, visible) =>
      s"${original.render} cannot proxy through itself (${visible.render})"
    case MissingNavigationTarget(surface, name) =>
      s"${surface.label} renderer target $name has no semantic navigation address"
    case MissingRenderedTarget(surface, name) =>
      s"${surface.label} expected renderer target $name is absent"
    case PhantomRenderedTarget(surface, name) =>
      s"${surface.label} produced unrecognised renderer target $name"
    case DuplicateExpectedTarget(surface, name, count) =>
      s"${surface.label} expected renderer target $name $count times"
    case DuplicateRenderedTarget(surface, name, count) =>
      s"${surface.label} rendered target $name $count times"
    case TargetIdentityMismatch(surface, name, expected, actual) =>
      s"${surface.label} target $name resolves to ${actual.render}, expected ${expected.render}"

/** A typed, one-to-one index proving which semantic address every rendered name represents. */
private[app] final case class RenderedTargetIndex[Name] private (
    surface: InteractionSurface,
    private val targets: Map[Name, Address],
    private val renderedByTarget: Map[Name, String],
    private val targetByRendered: Map[String, Name]
):
  def size: Int = targets.size

  def names: Set[Name] = targets.keySet

  def renderedNames: Set[String] = targetByRendered.keySet

  def contains(name: Name): Boolean = targets.contains(name)

  def rendered(name: Name): Option[String] = renderedByTarget.get(name)

  def addressOf(name: Name): Option[Address] = targets.get(name)

  /** Resolves a DOM name through the checked typed target space, never by parsing semantic ids. */
  def resolve(value: String): Option[(Name, Address)] =
    targetByRendered.get(value).flatMap(name => targets.get(name).map(name -> _))

  /** Restricts a checked index to one rendered plate such as one Codex page. */
  def restrict(keep: Set[Name]): RenderedTargetIndex[Name] =
    val keptTargets = targets.view.filterKeys(keep.contains).toMap
    val keptRendered = renderedByTarget.view.filterKeys(keep.contains).toMap
    RenderedTargetIndex(
      surface,
      keptTargets,
      keptRendered,
      keptRendered.map((name, rendered) => rendered -> name)
    )

  def validateRendered(actual: Vector[String]): Either[InteractionError, Unit] =
    RenderedTargetIndex.validateNames(surface, renderedNames, actual)

  def validate(
      decoration: InteractionDecoration[Name, Address]
  ): Either[InteractionError, InteractionDecoration[Name, Address]] =
    val expected = decoration.representation.visibleKey
    rendered(decoration.target) match
      case None =>
        Left(
          InteractionError.PhantomRenderedTarget(
            surface,
            decoration.target.toString
          )
        )
      case Some(name) =>
        addressOf(decoration.target) match
          case None => Left(InteractionError.MissingNavigationTarget(surface, name))
          case Some(actual) if actual != expected =>
            Left(InteractionError.TargetIdentityMismatch(surface, name, expected, actual))
          case Some(_) => Right(decoration)

private[app] object RenderedTargetIndex:

  /** Builds an index only when expected typed identities and actual renderer names agree exactly.
    */
  def build[Name](
      surface: InteractionSurface,
      expected: Vector[(Name, Address)],
      actual: Vector[String],
      renderName: Name => String
  ): Either[InteractionError, RenderedTargetIndex[Name]] =
    val expectedByRendered = expected.groupBy((name, _) => renderName(name))
    val duplicateExpected = expectedByRendered.toVector
      .collect { case (name, values) if values.lengthCompare(1) > 0 => name -> values.length }
      .sortBy(_._1)
      .headOption
    duplicateExpected match
      case Some((name, count)) =>
        Left(InteractionError.DuplicateExpectedTarget(surface, name, count))
      case None =>
        val expectedNames = expectedByRendered.keySet
        validateNames(surface, expectedNames, actual).map { _ =>
          val targets = expected.toMap
          val renderedByTarget = expected.map((name, _) => name -> renderName(name)).toMap
          RenderedTargetIndex(
            surface,
            targets,
            renderedByTarget,
            renderedByTarget.map((name, rendered) => rendered -> name)
          )
        }

  private def validateNames(
      surface: InteractionSurface,
      expected: Set[String],
      actual: Vector[String]
  ): Either[InteractionError, Unit] =
    val actualCounts = actual.groupMapReduce(identity)(_ => 1)(_ + _)
    val duplicate = actualCounts.toVector
      .collect { case (name, count) if count > 1 => name -> count }
      .sortBy(_._1)
      .headOption
    duplicate match
      case Some((name, count)) =>
        Left(InteractionError.DuplicateRenderedTarget(surface, name, count))
      case None =>
        val actualNames = actualCounts.keySet
        (
          expected.diff(actualNames).toVector.sorted.headOption,
          actualNames.diff(expected).toVector.sorted.headOption
        ) match
          case (Some(name), _) => Left(InteractionError.MissingRenderedTarget(surface, name))
          case (_, Some(name)) => Left(InteractionError.PhantomRenderedTarget(surface, name))
          case (None, None)    => Right(())

/** Distinguishes the two persistent interaction states a rendered object may communicate. */
private[app] enum InteractionRole:
  case Selection
  case Focus

/** Declares whether a rendered object is the semantic object itself or only its visible proxy. */
private[app] enum SemanticRepresentation[+Key]:
  case Direct(address: Key)
  case Proxy(original: Key, visible: Key)

  /** The semantic key whose interaction state survives representation changes. */
  def originalKey: Key = this match
    case Direct(address)  => address
    case Proxy(source, _) => source

  /** The semantic key represented by the named graphic at the current zoom. */
  def visibleKey: Key = this match
    case Direct(address)  => address
    case Proxy(_, target) => target

/** Carries semantic interaction state to a typed renderer target without changing its identity. */
private[app] final case class InteractionDecoration[+Name, +Key](
    target: Name,
    role: InteractionRole,
    representation: SemanticRepresentation[Key]
)

private[app] object InteractionDecoration:

  /** Gives compiled decorations one stable order for tests, labels, and reproducible snapshots. */
  def sortKey[Name, Key](
      value: InteractionDecoration[Name, Key],
      renderName: Name => String,
      renderKey: Key => String
  ): (String, Int, String, String) =
    val role = value.role match
      case InteractionRole.Selection => 0
      case InteractionRole.Focus     => 1
    (
      renderName(value.target),
      role,
      renderKey(value.representation.originalKey),
      renderKey(value.representation.visibleKey)
    )
