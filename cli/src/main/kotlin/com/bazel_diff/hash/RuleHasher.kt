package com.bazel_diff.hash

import com.bazel_diff.bazel.BazelRule
import com.bazel_diff.bazel.BazelSourceFileTarget
import com.bazel_diff.log.Logger
import com.google.common.annotations.VisibleForTesting
import java.nio.file.Path
import java.util.concurrent.ConcurrentMap
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RuleHasher(
    private val useCquery: Boolean,
    private val trackDepLabels: Boolean,
    private val fineGrainedHashExternalRepos: Set<String>
) : KoinComponent {
  private val logger: Logger by inject()
  private val sourceFileHasher: SourceFileHasher by inject()

  @VisibleForTesting class CircularDependencyException(message: String) : Exception(message)

  private fun raiseCircularDependency(
      depPath: LinkedHashSet<String>,
      begin: String
  ): CircularDependencyException {
    val sb =
        StringBuilder().appendLine("Circular dependency detected: ").append(begin).append(" -> ")
    val circularPath = depPath.toList().takeLastWhile { it != begin }
    circularPath.forEach { sb.append(it).append(" -> ") }
    sb.append(begin)
    return CircularDependencyException(sb.toString())
  }

  fun digest(
      rule: BazelRule,
      allRulesMap: Map<String, BazelRule>,
      ruleHashes: ConcurrentMap<String, TargetDigest>,
      sourceDigests: ConcurrentMap<String, ByteArray>,
      seedHash: ByteArray?,
      depPath: LinkedHashSet<String>?,
      ignoredAttrs: Set<String>,
      modifiedFilepaths: Set<Path>
  ): TargetDigest {
    val depPathClone = if (depPath != null) LinkedHashSet(depPath) else LinkedHashSet()
    if (depPathClone.contains(rule.name)) {
      throw raiseCircularDependency(depPathClone, rule.name)
    }
    depPathClone.add(rule.name)
    ruleHashes[rule.name]?.let {
      return it
    }

    val finalHashValue =
        targetSha256(trackDepLabels) {
          putDirectBytes(rule.digest(ignoredAttrs))
          putDirectBytes(seedHash)

          for (ruleInput in rule.ruleInputList(useCquery, fineGrainedHashExternalRepos)) {
            putDirectBytes(ruleInput.toByteArray())

            val inputRule = allRulesMap[ruleInput]
            when {
              inputRule == null && sourceDigests.containsKey(ruleInput) -> {
                putDirectBytes(sourceDigests[ruleInput])
              }
              inputRule?.name != null && inputRule.name != rule.name -> {
                val ruleInputHash =
                    digest(
                        inputRule,
                        allRulesMap,
                        ruleHashes,
                        sourceDigests,
                        seedHash,
                        depPathClone,
                        ignoredAttrs,
                        modifiedFilepaths)
                putTransitiveBytes(ruleInput, ruleInputHash.overallDigest)
              }
              else -> {
                val heuristicDigest =
                    sourceFileHasher.softDigest(
                        BazelSourceFileTarget(ruleInput, ByteArray(0)), modifiedFilepaths)
                when {
                  heuristicDigest != null -> {
                    logger.i {
                      "Source file $ruleInput picked up as an input for rule ${rule.name}"
                    }
                    sourceDigests[ruleInput] = heuristicDigest
                    putDirectBytes(heuristicDigest)
                  }
                  !ruleInput.startsWith("@") ->
                      logger.w {
                        "Unable to calculate digest for input $ruleInput for rule ${rule.name}"
                      }
                }
              }
            }
          }
        }

    return finalHashValue.also { ruleHashes[rule.name] = it }
  }
}
