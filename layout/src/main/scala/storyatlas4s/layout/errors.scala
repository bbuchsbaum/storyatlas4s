package storyatlas4s.layout

import storymodel4s.core.DomainError

/** Every way measurement or pagination can fail, so callers never see an exception. */
enum LayoutError:
  /** A page or style parameter outside its domain (non-positive size, page shorter than a line). */
  case InvalidSpec(field: String, reason: String)

  /** A measurer could not measure a run, or returned a table that does not fit the run. */
  case Measurement(measurer: String, reason: String)

  /** The metrics were measured for a different text than the flow being paginated. */
  case MetricsMismatch(reason: String)

  /** The measurer exists as a seam only on this platform (the JS DOM measurer before the app). */
  case Unavailable(measurer: String, reason: String)

  /** A storymodel4s domain error surfaced while slicing or spanning canonical text. */
  case Domain(error: DomainError)

  def message: String = this match
    case InvalidSpec(field, reason)    => s"invalid layout spec $field: $reason"
    case Measurement(measurer, reason) => s"measurement failed in $measurer: $reason"
    case MetricsMismatch(reason)       => s"metrics do not match the flow: $reason"
    case Unavailable(measurer, reason) => s"measurer $measurer unavailable: $reason"
    case Domain(error)                 => error.message
