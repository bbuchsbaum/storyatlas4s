package storyatlas4s.app

/** Distinguishes the two persistent interaction states a rendered object may communicate. */
enum InteractionRole:
  case Selection
  case Focus

/** Declares whether a rendered object is the semantic object itself or only its visible proxy. */
enum SemanticRepresentation[+Key]:
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

/** Carries semantic interaction state to a named renderer target without changing its identity. */
final case class InteractionDecoration[+Name, +Key](
    target: Name,
    role: InteractionRole,
    representation: SemanticRepresentation[Key]
)

object InteractionDecoration:

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
