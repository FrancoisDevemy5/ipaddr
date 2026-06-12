/**
  * Copyright 2017 RiskSense, Inc.
  * This file is part of ipaddr library.
  *
  * Ipaddr is free software licensed under the Apache License, Version 2.0 (the "License"); you
  * may not use this file except in compliance with the License. You may obtain a copy of the
  * License at
  *
  *         http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the
  * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
  * express or implied. See the License for the specific language governing permissions and
  * limitations under the License.
  */

package com.risksense.ipaddr

import scala.annotation.tailrec
import scala.collection.SortedSet
import scala.collection.SortedSetOps
import scala.collection.SpecificIterableFactory
import scala.collection.StrictOptimizedSortedSetOps
import scala.collection.mutable
import scala.util.hashing.MurmurHash3

/** Represents an unordered collection (set) of IpNetwork elements.
  *
  * @constructor creates a new IpSet.
  * @param networkSeq a sorted sequence of [[IpNetwork]] objects.
  */
case class IpSet private[ipaddr] (networkSeq: IndexedSeq[IpNetwork])
  extends SortedSet[IpNetwork]
    with SortedSetOps[IpNetwork, SortedSet, IpSet]
    with StrictOptimizedSortedSetOps[IpNetwork, SortedSet, IpSet] {

  lazy val volume: Long = networkSeq.map(_.size).sum[Long]

  lazy val isContiguous: Boolean = {
    if (networkSeq.size > 1) {
      val networkPairs = networkSeq.sliding(2)
      networkPairs.forall { netSeq => (netSeq(0).last + 1).equals(netSeq(1).first) }
    } else {
      true
    }
  }

  @throws[IpaddrException]
  lazy val ipRange: IpRange = {
    if (isContiguous) {
      if (networkSeq.isEmpty) {
        throw new IpaddrException("Cannot create IpRange from an empty IpSet.")
      } else {
        val ip1 = IpAddress(networkSeq.head.allHosts.head)
        val ip2 = IpAddress(networkSeq.last.allHosts.last)
        new IpRange(ip1, ip2)
      }
    } else {
      throw new IpaddrException(
        "The input IpRange does not represent a single contiguous sequence of addresses")
    }
  }

  override protected def fromSpecific(coll: IterableOnce[IpNetwork]): IpSet =
    IpSet.fromSpecific(coll)

  override protected def newSpecificBuilder: mutable.Builder[IpNetwork, IpSet] =
    IpSet.newBuilder

  override def empty: IpSet = IpSet.empty

  def map(f: IpNetwork => IpNetwork): IpSet = strictOptimizedMap(newSpecificBuilder, f)

  def flatMap(f: IpNetwork => IterableOnce[IpNetwork]): IpSet =
    strictOptimizedFlatMap(newSpecificBuilder, f)

  def collect(pf: PartialFunction[IpNetwork, IpNetwork]): IpSet =
    strictOptimizedCollect(newSpecificBuilder, pf)

  override val hashCode: Int = MurmurHash3.orderedHash(networkSeq.map(_.hashCode))

  final def ordering: Ordering[IpNetwork] = Ordering[IpNetwork]

  override def toString(): String = s"IpSet(${networkSeq.mkString(", ")})"

  override def equals(other: Any): Boolean = other match {
    case that: IpSet => that.canEquals(this) && networkMatch(that)
    case _ => false
  }

  def canEquals(other: Any): Boolean = other.isInstanceOf[IpSet]

  private def networkMatch(that: IpSet): Boolean = {
    val thisHashSet = this.networkSeq.map(_.hashCode).toSet
    val thatHashSet = that.networkSeq.map(_.hashCode).toSet
    thisHashSet.equals(thatHashSet)
  }

  def +(that: IpAddress): IpSet = {
    val newNetwork = IpNetwork(that, that.width)
    this + newNetwork
  }

  override def +(that: IpNetwork): IpSet = {
    if (this.contains(that)) {
      this
    } else {
      val newNetworkSeq: Seq[IpNetwork] = this.networkSeq :+ that
      IpSet(newNetworkSeq)
    }
  }

  def +(that: IpRange): IpSet = {
    val newSet = IpSet(that)
    val unionResult = this | newSet
    IpSet(unionResult)
  }

  def -(that: IpAddress): IpSet = {
    val net = IpNetwork(that, that.width)
    this - net
  }

  override def -(that: IpNetwork): IpSet = {
    val (matched, unmatched) = networkSeq.partition(_.contains(that))
    if (matched.isEmpty) {
      this
    } else {
      val newNetworks = BaseIp.cidrExclude(matched.head, that)
      IpSet(newNetworks ++ unmatched)
    }
  }

  def -(that: IpRange): IpSet = {
    val thatSet = IpSet(that)
    val diffResult = this.diff(thatSet)
    IpSet(diffResult.toSeq)
  }

  def iterator: Iterator[IpNetwork] = this.networkSeq.iterator

  override def iteratorFrom(start: IpNetwork): Iterator[IpNetwork] = {
    val matchFoundAt = this.networkSeq.indexWhere(_ >= start)
    if (matchFoundAt < 0) {
      Iterator.empty
    } else {
      this.networkSeq.drop(matchFoundAt).iterator
    }
  }

  def rangeImpl(from: Option[IpNetwork], until: Option[IpNetwork]): IpSet = {
    val beginIndex = from match {
      case Some(x) => this.networkSeq.indexWhere(_ >= x)
      case _ => 0
    }
    if (beginIndex < 0) {
      IpSet(Nil)
    } else {
      val endIndex = until match {
        case Some(x) => this.networkSeq.indexWhere(_ >= x, beginIndex)
        case _ => this.networkSeq.length
      }
      if (endIndex < 0) {
        IpSet(this.networkSeq.slice(beginIndex, this.networkSeq.length))
      } else {
        IpSet(this.networkSeq.slice(beginIndex, endIndex))
      }
    }
  }

  def <(that: IpSet): Boolean = (this.volume < that.volume) && this.subsetOf(that)

  def <=(that: IpSet): Boolean = this.subsetOf(that)

  def >(that: IpSet): Boolean = (this.volume > that.volume) && this.supersetOf(that)

  def >=(that: IpSet): Boolean = this.supersetOf(that)

  def supersetOf(that: IpSet): Boolean = that.subsetOf(this)

  def subsetOf(that: IpSet): Boolean = this.networkSeq.forall(that.contains(_))

  def contains(ipAddress: IpAddress): Boolean = contains(IpNetwork(ipAddress, ipAddress.width))

  def contains(network: IpNetwork): Boolean = this.exists(_.contains(network))

  def isDisjoint(that: IpSet): Boolean = this.intersect(that).isEmpty

  def ^(that: IpSet): IpSet = this.symmetricDiff(that)

  def symmetricDiff(that: IpSet): IpSet = {
    val common = this.intersect(that)
    val all = this | that
    val res = all.diff(common)
    IpSet(res.toSeq)
  }

  def intersect(that: IpSet): IpSet = {
    val thisNets = this.networkSeq
    val thatNets = that.networkSeq

    @tailrec
    def intersectRecurse(
        s1: Seq[IpNetwork],
        s2: Seq[IpNetwork],
        result: Seq[IpNetwork]): Seq[IpNetwork] = {
      if (s1.isEmpty || s2.isEmpty) {
        result
      } else {
        if (s1.head == s2.head) {
          intersectRecurse(s1.drop(1), s2.drop(1), result :+ s1.head)
        } else if (s1.head.contains(s2.head)) {
          intersectRecurse(s1, s2.drop(1), result :+ s2.head)
        } else if (s2.head.contains(s1.head)) {
          intersectRecurse(s1.drop(1), s2, result :+ s1.head)
        } else {
          if (s1.head < s2.head) {
            intersectRecurse(s1.drop(1), s2, result)
          } else {
            intersectRecurse(s1, s2.drop(1), result)
          }
        }
      }
    }

    val commonNets = intersectRecurse(thisNets, thatNets, Nil)
    IpSet(commonNets)
  }

  def &(that: IpSet): IpSet = this.intersect(that)

  def |(that: IpSet): IpSet = this.union(that)

  def union(that: IpSet): IpSet = {
    val res = super.union(that)
    IpSet(res.toSeq)
  }

  override def diff(that: collection.Set[IpNetwork]): IpSet = {
    val res = that.foldLeft(coll)((acc, elem) => acc - elem)
    IpSet(res.to(Seq))
  }
}

object IpSet extends SpecificIterableFactory[IpNetwork, IpSet] {

  def apply(): IpSet = new IpSet(Nil.toIndexedSeq)

  def apply(network: IpNetwork): IpSet = IpSet(Seq(network))

  def apply(ipRange: IpRange): IpSet = IpSet(ipRange.cidrs)

  def apply(ipSet: IpSet): IpSet = IpSet(ipSet.networkSeq.toSeq)

  def apply(netSeq: Seq[IpNetwork]): IpSet = {
    if (netSeq.isEmpty) {
      apply()
    } else {
      val mergedNetworks = BaseIp.cidrMerge(netSeq).toIndexedSeq
      new IpSet(mergedNetworks.sorted)
    }
  }

  override def empty: IpSet = IpSet()

  override def newBuilder: mutable.Builder[IpNetwork, IpSet] =
    new mutable.ImmutableBuilder[IpNetwork, IpSet](empty) {
      override def addOne(elem: IpNetwork): this.type = { elems = elems + elem; this }
    }

  override def fromSpecific(it: IterableOnce[IpNetwork]): IpSet = it match {
    case ipSet: IpSet => ipSet
    case seq: Seq[IpNetwork @unchecked] => IpSet(seq)
    case _ => IpSet(it.iterator.to(Seq))
  }
}
