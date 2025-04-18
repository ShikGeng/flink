/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.plan.rules.physical.common

import org.apache.flink.table.api.TableException
import org.apache.flink.table.connector.source.LookupTableSource
import org.apache.flink.table.legacy.sources.LookupableTableSource
import org.apache.flink.table.planner.hint.JoinStrategy
import org.apache.flink.table.planner.plan.`trait`.FlinkRelDistribution
import org.apache.flink.table.planner.plan.nodes.{FlinkConvention, FlinkConventions}
import org.apache.flink.table.planner.plan.nodes.logical._
import org.apache.flink.table.planner.plan.nodes.physical.batch.BatchPhysicalLookupJoin
import org.apache.flink.table.planner.plan.nodes.physical.common.{CommonPhysicalLegacyTableSourceScan, CommonPhysicalLookupJoin, CommonPhysicalTableSourceScan}
import org.apache.flink.table.planner.plan.nodes.physical.stream.StreamPhysicalLookupJoin
import org.apache.flink.table.planner.plan.rules.common.CommonTemporalTableJoinRule
import org.apache.flink.table.planner.plan.schema.TimeIndicatorRelDataType
import org.apache.flink.table.planner.plan.utils.{JoinUtil, LookupJoinUtil, TemporalJoinUtil}

import org.apache.calcite.plan.{RelOptRule, RelOptRuleCall, RelOptTable}
import org.apache.calcite.plan.RelOptRule.{any, operand}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.{Calc, TableScan}
import org.apache.calcite.rel.hint.RelHint
import org.apache.calcite.rex.RexProgram

import java.util

import scala.collection.JavaConversions._

/**
 * Base implementation for both
 * [[org.apache.flink.table.planner.plan.rules.physical.batch.BatchPhysicalLookupJoinRule]] and
 * [[org.apache.flink.table.planner.plan.rules.physical.stream.StreamPhysicalLookupJoinRule]].
 */
trait CommonLookupJoinRule extends CommonTemporalTableJoinRule {

  protected def matches(
      join: FlinkLogicalJoin,
      snapshot: FlinkLogicalSnapshot,
      tableScan: TableScan): Boolean = {

    // Lookup join is a kind of implementation of temporal table join
    if (!matches(snapshot)) {
      return false
    }

    // Temporal table join implemented by lookup join only supports on LookupTableSource
    if (!isTableSourceScan(tableScan) || !isLookupTableSource(tableScan)) {
      return false
    }

    // Temporal table join implemented by lookup join only supports processing-time join
    // Other temporal table join will be matched by CommonTemporalTableJoinRule
    val isProcessingTime = snapshot.getPeriod.getType match {
      case t: TimeIndicatorRelDataType if !t.isEventTime => true
      case _ => false
    }
    isProcessingTime
  }

  protected def isTableSourceScan(relNode: RelNode): Boolean = {
    relNode match {
      case _: FlinkLogicalLegacyTableSourceScan | _: CommonPhysicalLegacyTableSourceScan |
          _: FlinkLogicalTableSourceScan | _: CommonPhysicalTableSourceScan =>
        true
      case _ => false
    }
  }

  protected def isLookupTableSource(relNode: RelNode): Boolean = {
    relNode match {
      case scan: FlinkLogicalLegacyTableSourceScan =>
        scan.tableSource.isInstanceOf[LookupableTableSource[_]]
      case scan: CommonPhysicalLegacyTableSourceScan =>
        scan.tableSource.isInstanceOf[LookupableTableSource[_]]
      case scan: FlinkLogicalTableSourceScan =>
        scan.tableSource.isInstanceOf[LookupTableSource]
      case scan: CommonPhysicalTableSourceScan =>
        scan.tableSource.isInstanceOf[LookupTableSource]
      // TODO: find TableSource in FlinkLogicalIntermediateTableScan
      case _ => false
    }
  }

  // TODO Support `IS NOT DISTINCT FROM` in the future: FLINK-13509
  protected def validateJoin(join: FlinkLogicalJoin): Unit = {

    val filterNulls: Array[Boolean] = {
      val filterNulls = new util.ArrayList[java.lang.Boolean]
      JoinUtil.createJoinInfo(join.getLeft, join.getRight, join.getCondition, filterNulls)
      filterNulls.map(_.booleanValue()).toArray
    }

    if (filterNulls.contains(false)) {
      throw new TableException(
        s"LookupJoin doesn't support join condition contains 'a IS NOT DISTINCT FROM b' (or " +
          s"alternative '(a = b) or (a IS NULL AND b IS NULL)'), the join condition is " +
          s"'${join.getCondition}'")
    }
  }

  private def checkLookupShuffle(
      lookupHint: Option[RelHint],
      temporalTable: RelOptTable
  ): (Boolean, Boolean) = {
    lookupHint match {
      case Some(hint) =>
        val enableLookupShuffle = LookupJoinUtil.enableLookupShuffle(hint)
        val preferCustomShuffle = enableLookupShuffle &&
          LookupJoinUtil.tableProvidesCustomPartitioner(temporalTable)
        (enableLookupShuffle, preferCustomShuffle)
      case None => (false, false)
    }
  }

  protected def transform(
      join: FlinkLogicalJoin,
      input: FlinkLogicalRel,
      temporalTable: RelOptTable,
      calcProgram: Option[RexProgram]): CommonPhysicalLookupJoin

  protected def transformToLookupJoin(
      join: FlinkLogicalJoin,
      input: FlinkLogicalRel,
      temporalTable: RelOptTable,
      calcProgram: Option[RexProgram],
      convention: FlinkConvention): CommonPhysicalLookupJoin = {
    val joinInfo = join.analyzeCondition
    val cluster = join.getCluster
    val isStream = if (convention == FlinkConventions.STREAM_PHYSICAL) {
      true
    } else {
      false
    }

    val providedTrait = join.getTraitSet.replace(convention)
    var requiredTrait = input.getTraitSet.replace(convention)

    val lookupRelHint = join.getHints
      .stream()
      .filter(hint => JoinStrategy.isLookupHint(hint.hintName))
      .findFirst()

    val lookupHint = if (lookupRelHint.isPresent) {
      Option.apply(lookupRelHint.get())
    } else {
      Option.empty[RelHint]
    }

    val (enableLookupShuffle, preferCustomShuffle) = checkLookupShuffle(lookupHint, temporalTable)
    val enableHashShuffle = enableLookupShuffle && !preferCustomShuffle
    if (enableHashShuffle) {
      val joinKeyPairs = TemporalJoinUtil.getTemporalTableJoinKeyPairs(joinInfo, calcProgram).toList
      if (joinKeyPairs.nonEmpty) {
        val leftJoinKeys = joinKeyPairs.map(p => p.source).toArray
        requiredTrait =
          requiredTrait.plus(FlinkRelDistribution.hash(leftJoinKeys, requireStrict = true))
      }
    }

    val convInput = RelOptRule.convert(input, requiredTrait)

    if (isStream) {
      new StreamPhysicalLookupJoin(
        cluster,
        providedTrait,
        convInput,
        temporalTable,
        calcProgram,
        joinInfo,
        join.getJoinType,
        lookupHint,
        false,
        enableLookupShuffle,
        preferCustomShuffle)
    } else {
      new BatchPhysicalLookupJoin(
        cluster,
        providedTrait,
        convInput,
        temporalTable,
        calcProgram,
        joinInfo,
        join.getJoinType,
        lookupHint,
        enableLookupShuffle,
        preferCustomShuffle)
    }
  }
}

abstract class BaseSnapshotOnTableScanRule(description: String)
  extends RelOptRule(
    operand(
      classOf[FlinkLogicalJoin],
      operand(classOf[FlinkLogicalRel], any()),
      operand(classOf[FlinkLogicalSnapshot], operand(classOf[TableScan], any()))),
    description)
  with CommonLookupJoinRule {

  override def matches(call: RelOptRuleCall): Boolean = {
    val join = call.rel[FlinkLogicalJoin](0)
    val snapshot = call.rel[FlinkLogicalSnapshot](2)
    val tableScan = call.rel[TableScan](3)
    matches(join, snapshot, tableScan)
  }

  override def onMatch(call: RelOptRuleCall): Unit = {
    val join = call.rel[FlinkLogicalJoin](0)
    val input = call.rel[FlinkLogicalRel](1)
    val tableScan = call.rel[RelNode](3)

    validateJoin(join)
    val temporalJoin = transform(join, input, tableScan.getTable, None)
    call.transformTo(temporalJoin)
  }

}

abstract class BaseSnapshotOnCalcTableScanRule(description: String)
  extends RelOptRule(
    operand(
      classOf[FlinkLogicalJoin],
      operand(classOf[FlinkLogicalRel], any()),
      operand(
        classOf[FlinkLogicalSnapshot],
        operand(classOf[FlinkLogicalCalc], operand(classOf[TableScan], any())))
    ),
    description)
  with CommonLookupJoinRule {

  override def matches(call: RelOptRuleCall): Boolean = {
    val join = call.rel[FlinkLogicalJoin](0)
    val snapshot = call.rel[FlinkLogicalSnapshot](2)
    val tableScan = call.rel[TableScan](4)
    matches(join, snapshot, tableScan)
  }

  override def onMatch(call: RelOptRuleCall): Unit = {
    val join = call.rel[FlinkLogicalJoin](0)
    val input = call.rel[FlinkLogicalRel](1)
    val calc = call.rel[FlinkLogicalCalc](3)
    val tableScan = call.rel[RelNode](4)

    validateJoin(join)
    val temporalJoin = transform(join, input, tableScan.getTable, Some(calc.getProgram))
    call.transformTo(temporalJoin)
  }

}
