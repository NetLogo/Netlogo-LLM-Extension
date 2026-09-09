// ABOUTME: Registry of named configurations, each with its own ConfigStore and one cached provider
// ABOUTME: Backs per-agent model selection: an agent bound to a profile calls through that profile's provider
package org.nlogo.extensions.llm.config

import org.nlogo.extensions.llm.providers.LLMProvider
import scala.util.{Failure, Success, Try}

/**
 * One named configuration and the provider built from it.
 *
 * The provider is created on first use and then reused for the profile's
 * lifetime, so an async call that captured it keeps talking to the same
 * endpoint no matter what the modeler reassigns afterwards. A failed
 * creation is not cached: the next call tries again, so a transient error
 * (a server that was not up yet) does not poison the profile.
 */
final class Profile(val name: String, val config: ConfigStore, create: ConfigStore => Try[LLMProvider]) {
  private var cached: Option[LLMProvider] = None

  def provider: Try[LLMProvider] = synchronized {
    cached match {
      case Some(p) => Success(p)
      case None =>
        create(config).map { p =>
          cached = Some(p)
          p
        }
    }
  }
}

/**
 * Named configurations the modeler has loaded with llm:load-profile.
 *
 * Names are trimmed and lower-cased so "Fast" and "fast" are one profile.
 * The name reserved for the global configuration is rejected here so no
 * profile can shadow it. Loading a name that already exists replaces the
 * whole entry, cached provider included.
 */
final class ProfileStore(create: ConfigStore => Try[LLMProvider]) {
  private var profiles: Map[String, Profile] = Map.empty

  private def normalise(name: String): String = name.trim.toLowerCase

  def load(name: String, config: Map[String, String]): Unit = {
    val key = normalise(name)
    if (key.isEmpty)
      throw new IllegalArgumentException("Profile name cannot be blank")
    if (key == ProfileStore.DefaultName)
      throw new IllegalArgumentException(
        s"'${ProfileStore.DefaultName}' is reserved for the global configuration. Choose another name."
      )
    val store = new ConfigStore()
    store.loadFromMap(config)
    val profile = new Profile(key, store, create)
    synchronized { profiles = profiles.updated(key, profile) }
  }

  def get(name: String): Option[Profile] = synchronized { profiles.get(normalise(name)) }

  def contains(name: String): Boolean = get(name).isDefined

  def names: Seq[String] = synchronized { profiles.keys.toSeq.sorted }

  def provider(name: String): Try[LLMProvider] =
    get(name) match {
      case Some(profile) => profile.provider
      case None =>
        Failure(new IllegalArgumentException(
          s"no profile named '${name.trim}'. Loaded profiles: ${describeLoaded}"
        ))
    }

  def describeLoaded: String = {
    val loaded = names
    if (loaded.isEmpty) "none" else loaded.mkString(", ")
  }
}

object ProfileStore {
  /** The name that means "the global configuration", never a loaded profile. */
  val DefaultName: String = "default"
}
