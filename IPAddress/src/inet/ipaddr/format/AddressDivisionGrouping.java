/*
 * Copyright 2017 Sean C Foley
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     or at
 *     https://github.com/seancfoley/IPAddress/blob/master/LICENSE
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package inet.ipaddr.format;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import inet.ipaddr.Address;
import inet.ipaddr.Address.SegmentValueProvider;
import inet.ipaddr.AddressNetwork.AddressSegmentCreator;
import inet.ipaddr.AddressSection;
import inet.ipaddr.AddressSegment;
import inet.ipaddr.AddressSegmentSeries;
import inet.ipaddr.AddressTypeException;
import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressSection;
import inet.ipaddr.format.AddressDivisionGrouping.StringOptions.Wildcards;
import inet.ipaddr.format.util.AddressDivisionWriter;
import inet.ipaddr.format.util.AddressSegmentParams;

/**
 * AddressDivisionGrouping objects consist of a series of AddressDivision objects, each division containing one or more segments.
 * <p>
 * AddressDivisionGrouping objects are immutable.  This also makes them thread-safe.
 * 
 *  @author sfoley
 */
public class AddressDivisionGrouping implements AddressDivisionSeries, Comparable<AddressDivisionGrouping> {

	private static final long serialVersionUID = 3L;
	
	/* caches objects to avoid recomputing them */
	protected static class SectionCache<R extends AddressSegmentSeries> {
		public R lower;
		public R upper;
		
		public SectionCache() {}
		
		R get(boolean lowest) {
			return lowest ? lower : upper;
		}
		
		void set(boolean lowest, R newSeries) {
			if(lowest) {
				lower = newSeries;
			} else {
				upper = newSeries;
			}
		}
	}
	
	/* the various string representations - these fields are for caching */
	protected static class StringCache {
		public String canonicalString;
		public String hexString;
		public String hexStringPrefixed;
	}
	
	/* the address bytes */
	private transient byte[] lowerBytes, upperBytes;
	
	protected final AddressDivision divisions[];
	private transient BigInteger cachedCount;
	protected Integer cachedPrefix; //null indicates this field not initialized, -1 indicates the prefix len is null
	
	/* for addresses not multiple, we must check each segment, so we cache */
	private transient Boolean isMultiple;
	
	protected int hashCode;
	
	public AddressDivisionGrouping(AddressDivision divisions[]) {
		this.divisions = divisions;
	}
	
	protected void initCachedValues(Integer cachedNetworkPrefix, BigInteger cachedCount) {
		this.cachedPrefix = cachedNetworkPrefix;
		this.cachedCount = cachedCount;
	}
	
	@Override
	public AddressDivision getDivision(int index) {
		return divisions[index];
	}

	@Override
	public int getDivisionCount() {
		return divisions.length;
	}
	
	@Override
	public int getBitCount() {
		int count = getDivisionCount();
		int bitCount = 0;
		for(int i = 0; i < count; i++) {
			bitCount += getDivision(i).getBitCount();
		}
		return bitCount;
	}
	
	protected byte[] getCachedBytes() {
		return lowerBytes;
	}
	
	protected static byte[] getCachedBytes(AddressDivisionGrouping grouping) {
		return grouping.getCachedBytes();
	}
	
	/**
	 * Gets the bytes for the lowest address in the range represented by this address.
	 * 
	 * Since bytes are signed values while addresses are unsigned, values greater than 127 are
	 * represented as the (negative) two's complement value of the actual value.
	 * You can get the unsigned integer value i from byte b using i = 0xff & b.
	 * 
	 * @return
	 */
	@Override
	public byte[] getBytes() {
		if(lowerBytes == null) {
			setBytes(getBytesImpl(true));
		}
		return lowerBytes.clone();
	}

	/**
	 * Gets the value for the lowest address in the range represented by this address division.
	 * 
	 * If the value fits in the specified array, the same array is returned with the value.  
	 * Otherwise, a new array is allocated and returned with the value.
	 * 
	 * You can use {@link #getBitCount()} to determine the required array length for the bytes.
	 * 
	 * Since bytes are signed values while addresses are unsigned, values greater than 127 are
	 * represented as the (negative) two's complement value of the actual value.
	 * You can get the unsigned integer value i from byte b using i = 0xff & b.
	 * 
	 * @return
	 */
	@Override
	public byte[] getBytes(byte bytes[]) {
		byte cached[] = lowerBytes;
		if(cached == null) {
			setBytes(cached = getBytesImpl(true));
		}
		return getBytes(bytes, cached);
	}

	private byte[] getBytes(byte[] bytes, byte[] cached) {
		int byteCount = (getBitCount() + 7) >> 3;
		if(bytes == null || bytes.length < byteCount) {
			return cached.clone();
		} 
		System.arraycopy(cached, 0, bytes, 0, byteCount);
		return bytes;
	}
	
	/**
	 * Gets the bytes for the highest address in the range represented by this address.
	 * 
	 * @return
	 */
	@Override
	public byte[] getUpperBytes() {
		if(!isMultiple()) {
			return getBytes();
		}
		byte cached[] = upperBytes;
		if(cached == null) {
			setUpperBytes(cached = getBytesImpl(false));
		}
		return cached.clone();
	}
	
	@Override
	public byte[] getUpperBytes(byte bytes[]) {
		if(!isMultiple()) {
			return getBytes(bytes);
		}
		byte cached[] = upperBytes;
		if(cached == null) {
			setUpperBytes(cached = getBytesImpl(false));
		}
		return getBytes(bytes, cached);
	}
	
	protected byte[] getBytesImpl(boolean low) {
		byte bytes[] = new byte[(getBitCount() + 7) >> 3];
		int byteCount = bytes.length;
		int divCount = getDivisionCount();
		for(int k = divCount - 1, byteIndex = byteCount - 1, bitIndex = 8; k >= 0; k--) {
			AddressDivision div = getDivision(k);
			long segmentValue = low ? div.getLowerValue() : div.getUpperValue();
			int divBits = div.getBitCount();
			//write out this entire segment
			while(divBits > 0) {
				bytes[byteIndex] |= segmentValue << (8 - bitIndex);
				segmentValue >>= bitIndex;
				if(divBits < bitIndex) {
					bitIndex -= divBits;
					break;
				} else {
					divBits -= bitIndex;
					bitIndex = 8;
					byteIndex--;
				}
			}
		}
		return bytes;
	}
	
	protected void setBytes(byte bytes[]) {
		lowerBytes = bytes;
	}
	
	protected void setUpperBytes(byte bytes[]) {
		upperBytes = bytes;
	}
	
	@Override
	public boolean isPrefixed() {
		return getPrefixLength() != null;
	}
	
	@Override
	public Integer getPrefixLength() {
		return cachedPrefix;
	}
	
	/**
	 * Returns the smallest prefix length possible
	 * such that this address paired with that prefix length represents the exact same range of addresses.
	 *
	 * @return the prefix length
	 */
	@Override
	public int getMinPrefix() {
		int count = getDivisionCount();
		int totalPrefix = getBitCount();
		for(int i = count - 1; i >= 0 ; i--) {
			AddressDivision div = getDivision(i);
			int segBitCount = div.getBitCount();
			int segPrefix = div.getMinPrefix();
			if(segPrefix == segBitCount) {
				break;
			} else {
				totalPrefix -= segBitCount;
				if(segPrefix != 0) {
					totalPrefix += segPrefix;
					break;
				}
			}
		}
		return totalPrefix;
	}

	/**
	 * Returns a prefix length for which the range of this segment grouping can be specified only using the section's lower value and the prefix length
	 * 
	 * If no such prefix exists, returns null
	 * 
	 * If this segment grouping represents a single value, returns the bit length
	 * 
	 * @return the prefix length or null
	 */
	@Override
	public Integer getEquivalentPrefix() {
		int count = getDivisionCount();
		int totalPrefix = 0;
		for(int i = 0; i < count; i++) {
			AddressDivision div = getDivision(i);
			int divPrefix = div.getMinPrefix();
			if(!div.matchesWithMask(div.getLowerValue(), ~0 << (div.getBitCount() - divPrefix))) {
				return null;
			}
			if(divPrefix < div.getBitCount()) {
				//remaining segments must be full range or we return null
				for(i++; i < count; i++) {
					AddressDivision laterDiv = getDivision(i);
					if(!laterDiv.isFullRange()) {
						return null;
					}
				}
				return totalPrefix + divPrefix;
			}
			totalPrefix += divPrefix;
		}
		return totalPrefix;
	}
	
	/**
	 * gets the count of addresses that this address may represent
	 * 
	 * If this address is not a CIDR and it has no range, then there is only one such address.
	 * 
	 * @return
	 */
	@Override
	public BigInteger getCount() {
		if(cachedCount == null) {
			cachedCount = getCountImpl();
		}
		return cachedCount;
	}

	protected BigInteger getCountImpl() {
		BigInteger result = BigInteger.ONE;
		if(getDivisionCount() > 0) {
			if(isMultiple()) {
				int count = getDivisionCount();
				for(int i = 0; i < count; i++) {
					long segCount = getDivision(i).getDivisionValueCount();
					result = result.multiply(BigInteger.valueOf(segCount));
				}
			}
		}
		return result;
	}
	
	@Override
	public int isMore(AddressDivisionSeries other) {
		if(!isMultiple()) {
			return other.isMultiple() ? -1 : 0;
		}
		if(!other.isMultiple()) {
			return 1;
		}
		return getCount().compareTo(other.getCount());
	}
	
	/**
	 * @return whether this address represents more than one address.
	 * Such addresses include CIDR/IP addresses (eg 1.2.3.4/11) or wildcard addresses (eg 1.2.*.4) or range addresses (eg 1.2.3-4.5)
	 */
	@Override
	public boolean isMultiple() {
		Boolean result = isMultiple;
		if(result == null) {
			for(int i = divisions.length - 1; i >= 0; i--) {//go in reverse order, with prefixes multiple more likely to show up in last segment
				AddressDivision seg = divisions[i];
				if(seg.isMultiple()) {
					return isMultiple = true;
				}
			}
			return isMultiple = false;
		}
		return result;
	}
	
	/**
	 * @return there is a prefix and there are multiple addresses associated with that prefix
	 */
	@Override
	public boolean isMultipleByPrefix() {
		return cachedPrefix != null && cachedPrefix < getBitCount();
	}

	@Override
	public boolean isRangeEquivalentToPrefix() {
		if(cachedPrefix == null) {
			return !isMultiple();
		}
		return isRangeEquivalent(cachedPrefix);
	}
	
	public boolean isRangeEquivalent(int prefix) {
		if(prefix == 0) {
			return true;
		}
		//we check the range of each division to see if it matches the prefix
		int nonPrefixBits = Math.max(0, getBitCount() - prefix);
		int divisionCount = getDivisionCount();
		for(int i = divisionCount - 1; i >= 0; i--) {
			AddressDivision division = getDivision(i);
			int bitCount = division.getBitCount();
			if(nonPrefixBits == 0) {
				if(division.isMultiple()) {
					return false;
				}
			} else {
				int nonPrefixDivisionBits = Math.min(bitCount, nonPrefixBits);
				long divisionPrefixMask = ~0L << nonPrefixDivisionBits;
				long lower = division.getLowerValue();
				if((lower | ~divisionPrefixMask) != division.getUpperValue() || (lower & divisionPrefixMask) != lower) {
					return false;
				}
				nonPrefixBits = Math.max(0, nonPrefixBits - bitCount);
			}
		}
		return true;
	}
	
	@Override
	public int hashCode() {
		int res = hashCode;
		if(res == 0) {
			int fullResult = 1;
			int count = getDivisionCount();
			for(int i = 0; i < count; i++) {
				AddressDivision combo = getDivision(i);
				long value = combo.getLowerValue();
				long shifted = value >>> 32;
				int adjusted = (int) ((shifted == 0) ? value : (value ^ shifted));
				fullResult = 31 * fullResult + adjusted;
				long upperValue = combo.getUpperValue();
				if(upperValue != value) {
					shifted = upperValue >>> 32;
					adjusted = (int) ((shifted == 0) ? upperValue : (upperValue ^ shifted));
					fullResult = 31 * fullResult + adjusted;
				}
			}
			hashCode = res = fullResult;
		}
		return res;
	}
	
	protected boolean isSameGrouping(AddressDivisionGrouping other) {
		AddressDivision oneSegs[] = divisions;
		AddressDivision twoSegs[] = other.divisions;
		if(oneSegs.length != twoSegs.length) {
			return false;
		}
		for(int i = 0; i < oneSegs.length; i++) {
			AddressDivision one = oneSegs[i];
			AddressDivision two = twoSegs[i];
			if(!one.isSameValues(two)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean equals(Object o) {
		if(o == this) {
			return true;
		}
		if(o instanceof AddressDivisionGrouping) {
			AddressDivisionGrouping other = (AddressDivisionGrouping) o;
			return other.isSameGrouping(this); //we call isSameGrouping on the other object to defer to subclasses
		}
		return false;
	}

	@Override
	public int compareTo(AddressDivisionGrouping other) {
		return IPAddress.addressComparator.compare(this, other);
	}

	@Override
	public String toString() {
		return Arrays.asList(divisions).toString();
	}
	
	@Override
	public boolean isZero() {
		int divCount = getDivisionCount();
		for(int i = 0; i < divCount; i++) {
			if(!getDivision(i).isZero()) {
				return false;
			}
		}
		return true;
	}
	
	@Override
	public boolean isFullRange() {
		int divCount = getDivisionCount();
		//if we have calculated prefix already, we use it
		//otherwise we do not, considering that calculating the prefix
		//may also require checking each division
		//in cases where calculating prefix is quicker, we override this method
		boolean isPrefixCached = cachedPrefix != null;
		int bitsSoFar = 0;
		for(int i = 0; i < divCount; i++) {
			AddressDivision div = getDivision(i);
			if(!div.isFullRange()) {
				return false;
			}
			if(isPrefixCached) {
				bitsSoFar += getBitCount();
				if(bitsSoFar >= cachedPrefix) {
					break;
				}
			}
		}
		return true;
	}

	/**
	 * Across the address prefixes are:
	 * IPv6: (null):...:(null):(1 to 16):(0):...:(0)
	 * or IPv4: ...(null).(1 to 8).(0)...
	 */
	protected static Integer getSegmentPrefixLength(int bitsPerSegment, Integer prefixLength, int segmentIndex) {
		if(prefixLength != null) {
			return getSegmentPrefixLengthNonNull(bitsPerSegment, prefixLength, segmentIndex);
		}
		return null;
	}
	
	protected static Integer getSegmentPrefixLengthNonNull(int bitsPerSegment, int prefixLength, int segmentIndex) {
		int decrement;
		if(bitsPerSegment == 8) {
			decrement = segmentIndex << 3;
		} else if(bitsPerSegment == 16) {
			decrement = segmentIndex << 4;
		} else {
			decrement = segmentIndex * bitsPerSegment;
		}
		return getSegmentPrefixLength(bitsPerSegment, prefixLength - decrement);
	}
	
	/**
	 * Across the address prefixes are:
	 * IPv6: (null):...:(null):(1 to 16):(0):...:(0)
	 * or IPv4: ...(null).(1 to 8).(0)...
	 */
	protected static Integer getSegmentPrefixLength(int segmentBits, int segmentPrefixedBits) {
		if(segmentPrefixedBits <= 0) {
			return 0; //none of the bits in this segment matter
		} else if(segmentPrefixedBits <= segmentBits) {
			return segmentPrefixedBits;//some of the bits in this segment matter
		}
		return null; //all the bits in this segment matter
	}

	protected int getAdjustedPrefix(boolean nextSegment, int bitsPerSegment, boolean skipBitCountPrefix) {
		Integer prefix = getPrefixLength();
		int bitCount = getBitCount();
		if(nextSegment) {
			if(prefix == null) {
				if(getMinPrefix() == 0) {
					return 0;
				}
				return bitCount;
			}
			if(prefix == bitCount) {
				return bitCount;
			}
			int existingPrefixLength = prefix.intValue();
			int adjustment = existingPrefixLength % bitsPerSegment;
			return existingPrefixLength + bitsPerSegment - adjustment;
		} else {
			if(prefix == null) {
				if(getMinPrefix() == 0) {
					return 0;
				}
				if(skipBitCountPrefix) {
					prefix = bitCount;
				} else {
					return bitCount;
				}
			} else if(prefix == 0) {
				return 0;
			}
			int existingPrefixLength = prefix.intValue();
			int adjustment = ((existingPrefixLength - 1) % bitsPerSegment) + 1;
			return existingPrefixLength - adjustment;
		}
	}
	
	protected int getAdjustedPrefix(int adjustment, boolean floor, boolean ceiling) {
		Integer prefix = getPrefixLength();
		
		
		if(prefix == null) {
			if(getMinPrefix() == 0) {
				prefix = 0;
			} else {
				prefix = getBitCount();
			}
		}
		
		
		int result = prefix + adjustment;
		if(ceiling) {
			result = Math.min(getBitCount(), result);
		}
		if(floor) {
			result = Math.max(0, result);
		}
		return result;
	}

	protected static <R extends AddressSegmentSeries> R getSingle(R original, Supplier<R> singleFromMultipleCreator) {
		if(!original.isPrefixed() && !original.isMultiple()) {
			return original;
		}
		return singleFromMultipleCreator.get();
	}
	
	/*
	 * The next methods deal with creating AddressSegment objects and not AddressDivision object.
	 * 
	 * The basic difference is that segments are limited in size, and we have creator objects capable of creating all shapes and sizes of segments.
	 * 
	 * Address division objects are more diverse.
	 */
	protected static <S extends AddressSegment> S[] toPrefixedSegments(
			Integer sectionPrefixBits,
			S segments[],
			int segmentBitCount,
			AddressSegmentCreator<S> segmentCreator,
			BiFunction<S, Integer, S> segProducer,
			boolean alwaysClone) {
		if(sectionPrefixBits != null) {
			return setPrefixedSegments(sectionPrefixBits, segments.clone(), segmentBitCount, segmentCreator, segProducer);
		}
		if(alwaysClone) {
			return segments.clone();
		}
		return segments;
	}
	
	private static <S extends AddressSegment> S[] setPrefixedSegments(
			int sectionPrefixBits,
			S segments[],
			int segmentBitCount,
			AddressSegmentCreator<S> segmentCreator,
			BiFunction<S, Integer, S> segProducer) {
			for(int i = 0; i < segments.length; i++) {
				Integer pref = getSegmentPrefixLengthNonNull(segmentBitCount, sectionPrefixBits, i);
				if(pref != null) {
					segments[i] = segProducer.apply(segments[i], pref);
					if(++i < segments.length) {
						S allSeg = segmentCreator.createSegment(0, 0);
						do {
							segments[i] = allSeg;
						} while(++i < segments.length);
					}
				}
			}
		return segments;
	}
	
	protected interface SegFunction<T, U, V, R> {
	    R apply(T t, U u, V v);
	}
	
	protected static <R extends AddressSection, S extends AddressSegment> S[] setPrefixed(
			R original,
			int newPrefixBits,
			S segments[],
			int segmentBitCount,
			boolean noShrink,
			AddressSegmentCreator<S> segmentCreator,
			BiFunction<S, Integer, S> prefixApplier,//this one takes just the new prefix like above and applies it
			SegFunction<S, Integer, Integer, S> prefixSetter //this one takes both new and old prefix and both zeros out old pref and applies new one
			) {
		Integer oldPrefix = original.getPrefixLength();
		if(oldPrefix == null || oldPrefix > newPrefixBits) {
			segments = toPrefixedSegments(newPrefixBits, segments, segmentBitCount, segmentCreator, prefixApplier, false);
		} else if(oldPrefix < newPrefixBits) {
			if(noShrink) {
				return segments;
			}
			segments = segments.clone();
			for(int i = 0; i < segments.length; i++) {
				Integer newPref = getSegmentPrefixLengthNonNull(segmentBitCount, newPrefixBits, i);
				Integer oldPref = getSegmentPrefixLengthNonNull(segmentBitCount, oldPrefix, i);
				segments[i] = prefixSetter.apply(segments[i], oldPref, newPref);
				if(newPref != null) {
					if(++i < segments.length) {
						S zeroSeg = segmentCreator.createSegment(0, 0);
						do {
							segments[i] = zeroSeg;
						} while(++i < segments.length);
					}
				}
			}
		}
		return segments;
	}
	
	protected static <R extends AddressSection, S extends AddressSegment> S[] removePrefix(
			R original,
			S segments[],
			int segmentBitCount,
			SegFunction<S, Integer, Integer, S> prefixSetter //this one takes both new and old prefix and both zeros out old pref and applies new one
			) {
		Integer oldPrefix = original.getPrefixLength();
		if(oldPrefix != null) {
			segments = segments.clone();
			for(int i = 0; i < segments.length; i++) {
				Integer oldPref = getSegmentPrefixLengthNonNull(segmentBitCount, oldPrefix, i);
				segments[i] = prefixSetter.apply(segments[i], oldPref, null);
			}
		}
		return segments;
	}
	
	protected static <T extends AddressSegment> T[] toSegments(
			long val,
			int valueByteLength,
			int segmentCount,
			int bytesPerSegment,
			int bitsPerSegment,
			int maxValuePerSegment,
			AddressSegmentCreator<T> creator,
			Integer prefixLength) {
		T segments[] = creator.createSegmentArray(segmentCount);
		int segmentMask = ~(~0 << bitsPerSegment);
		for(int segmentIndex = segmentCount - 1; segmentIndex >= 0; segmentIndex--) {
			Integer segmentPrefixLength = IPAddressSection.getSegmentPrefixLength(bitsPerSegment, prefixLength, segmentIndex);
			int value = segmentMask & (int) val;
			val >>>= bitsPerSegment;
			segments[segmentIndex] = creator.createSegment(value, segmentPrefixLength);
		}
		return segments;
	}
	
	protected static <S extends AddressSegment> S[] toSegments(
			SegmentValueProvider lowerValueProvider,
			SegmentValueProvider upperValueProvider,
			int segmentCount,
			int bytesPerSegment,
			int bitsPerSegment,
			int maxValuePerSegment,
			AddressSegmentCreator<S> creator,
			Integer prefixLength) {
		S segments[] = creator.createSegmentArray(segmentCount);
		for(int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++) {
			Integer segmentPrefixLength = IPAddressSection.getSegmentPrefixLength(bitsPerSegment, prefixLength, segmentIndex);
			if(segmentPrefixLength != null && segmentPrefixLength == 0) {
				S allSeg = creator.createSegment(0, maxValuePerSegment, 0);
				do {
					segments[segmentIndex] = allSeg;
				} while(++segmentIndex < segmentCount);
				break;
			}
			
			int value = 0, value2 = 0;
			if(lowerValueProvider == null) {
				value = upperValueProvider.getValue(segmentIndex, bytesPerSegment);
			} else {
				value = lowerValueProvider.getValue(segmentIndex, bytesPerSegment);
				if(upperValueProvider != null) {
					value2 = upperValueProvider.getValue(segmentIndex, bytesPerSegment);
				}
			}
			segments[segmentIndex] = (lowerValueProvider != null && upperValueProvider != null) ? creator.createSegment(value, value2, segmentPrefixLength) : creator.createSegment(value, segmentPrefixLength);
		}
		return segments;
	}
	
	protected static <S extends AddressSegment> S[] toSegments(
			byte bytes[],
			//byte bytes2[],
			int segmentCount,
			int bytesPerSegment,
			int bitsPerSegment,
			int maxValuePerSegment,
			AddressSegmentCreator<S> creator,
			Integer prefixLength) {
		S segments[] = creator.createSegmentArray(segmentCount);
		for(int i = 0, segmentIndex = 0; i < bytes.length; i += bytesPerSegment, segmentIndex++) {
			Integer segmentPrefixLength = IPAddressSection.getSegmentPrefixLength(bitsPerSegment, prefixLength, segmentIndex);
			if(segmentPrefixLength != null && segmentPrefixLength == 0) {
				S allSeg = creator.createSegment(0, maxValuePerSegment, 0);
				do {
					segments[segmentIndex] = allSeg;
				} while(++segmentIndex < segmentCount);
				break;
			}
			int value = 0;
			int k = bytesPerSegment + i;
			for(int j = i; j < k; j++) {
				int byteValue = 0xff & bytes[j];
				value <<= 8;
				value |= byteValue;
			}
			segments[segmentIndex] = creator.createSegment(value, segmentPrefixLength);
		}
		return segments;
	}
	
	protected static <R extends AddressSection, S extends AddressSegment> S[] createSingle(
			R original,
			AddressSegmentCreator<S> segmentCreator,
			IntFunction<S> segProducer) {
		int segmentCount = original.getSegmentCount();
		S segs[] = segmentCreator.createSegmentArray(segmentCount);
		for(int i = 0; i < segmentCount; i++) {
			segs[i] = segProducer.apply(i);
		}
		return segs;
	}
	
	protected static <R extends AddressSegmentSeries> SectionCache<R> getSectionCache(
			R section,
			Supplier<SectionCache<R>> sectionCacheGetter,
			Supplier<SectionCache<R>> sectionCacheCreator) {
		SectionCache<R> result = sectionCacheGetter.get();
		if(result == null) {
			synchronized(section) {
				result = sectionCacheGetter.get();
				if(result == null) {
					result = sectionCacheCreator.get();
				}
			}
		}
		return result;
	}

	protected static <T extends Address, R extends AddressSection, S extends AddressSegment> R getLowestOrHighestSection(
			R section, AddressCreator<?, R, ?, S> creator, boolean lowest, IntFunction<S> segProducer, Supplier<SectionCache<R>> sectionCacheSupplier) { 
		return getSingle(section, () -> {
			R result;
			SectionCache<R> cache = sectionCacheSupplier.get();
			if((result = cache.get(lowest)) == null) {
				S[] segs = createSingle(section, creator, segProducer);
				result = creator.createSectionInternal(segs);
				cache.set(lowest, result);
			}
			return result;
		});
	}
	
	protected static <T extends Address, R extends AddressSection, S extends AddressSegment> T getLowestOrHighestAddress(
			T addr, AddressCreator<T, R, ?, S> creator, boolean lowest, Supplier<R> sectionProducer, Supplier<SectionCache<T>> sectionCacheSupplier) { 
		return getSingle(addr, () -> {
			T result;
			SectionCache<T> cache = sectionCacheSupplier.get();
			if((result = cache.get(lowest)) == null) {
				result = creator.createAddress(sectionProducer.get());
				cache.set(lowest, result);
				return result;
			}
			return result;
		});
	}
	
	protected static <R extends AddressSection, S extends AddressSegment> R reverseSegments(R section, AddressCreator<?, R, ?, S> creator, IntFunction<S> segProducer, boolean removePrefix) {
		int count = section.getSegmentCount();
		S newSegs[] = creator.createSegmentArray(count);
		int halfCount = count >>> 1;
		int i = 0;
		boolean isSame = !removePrefix || !section.isPrefixed();//when reversing, the prefix must go
		for(int j = count - 1; i < halfCount; i++, j--) {
			newSegs[j] = segProducer.apply(i);
			newSegs[i] = segProducer.apply(j);
			if(isSame && !(newSegs[i].equals(section.getSegment(i)) && newSegs[j].equals(section.getSegment(j)))) {
				isSame = false;
			}
		}
		if((count & 1) == 1) {//the count is odd, handle the middle one
			newSegs[i] = segProducer.apply(i);
			if(isSame && !newSegs[i].equals(section.getSegment(i))) {
				isSame = false;
			}
		}
		if(isSame) {
			return section;//We can do this because for ipv6 startIndex stays the same and for mac startIndex and extended stays the same
		}
		return creator.createSectionInternal(newSegs);
	}
	
	protected static <R extends AddressSection, S extends AddressSegment> R reverseBits(
			boolean perByte, R section, AddressCreator<?, R, ?, S> creator, IntFunction<S> segBitReverser, boolean removePrefix) {
		if(perByte) {
			boolean isSame = !removePrefix || !section.isPrefixed();//when reversing, the prefix must go
			int count = section.getSegmentCount();
			S newSegs[] = creator.createSegmentArray(count);
			for(int i = 0; i < count; i++) {
				newSegs[i] = segBitReverser.apply(i);
				if(isSame && !newSegs[i].equals(section.getSegment(i))) {
					isSame = false;
				}
			}
			if(isSame) {
				return section;//We can do this because for ipv6 startIndex stays the same and for mac startIndex and extended stays the same
			}
			return creator.createSectionInternal(newSegs);
		}
		return reverseSegments(section, creator, segBitReverser, removePrefix);
	}
	
	protected static <R extends AddressSection, S extends AddressSegment> R reverseBytes(
			boolean perSegment, R section, AddressCreator<?, R, ?, S> creator, IntFunction<S> segByteReverser, boolean removePrefix) {
		if(perSegment) {
			boolean isSame = !removePrefix || !section.isPrefixed();//when reversing, the prefix must go
			int count = section.getSegmentCount();
			S newSegs[] = creator.createSegmentArray(count);
			for(int i = 0; i < count; i++) {
				newSegs[i] = segByteReverser.apply(i);
				if(isSame && !newSegs[i].equals(section.getSegment(i))) {
					isSame = false;
				}
			}
			if(isSame) {
				return section;//We can do this because for ipv6 startIndex stays the same and for mac startIndex and extended stays the same
			}
			return creator.createSectionInternal(newSegs);
		}
		return reverseSegments(section, creator, segByteReverser, removePrefix);
	}

	protected static interface GroupingCreator<S extends AddressDivisionBase> {
		S createDivision(long value, long upperValue, int bitCount, int radix);
	}
	
	protected <S extends AddressDivisionBase> S[] createNewDivisions(int bitsPerDigit, GroupingCreator<S> groupingCreator, IntFunction<S[]> groupingArrayCreator) {
		return createNewPrefixedDivisions(bitsPerDigit, null, 
				(value, upperValue, bitCount, radix, prefixLength) -> groupingCreator.createDivision(value, upperValue, bitCount, radix),
				groupingArrayCreator);
	}
	
	protected static interface PrefixedGroupingCreator<S extends AddressDivisionBase> {
		S createDivision(long value, long upperValue, int bitCount, int radix, Integer prefixLength);
	}
	
	protected <S extends AddressDivisionBase> S[] createNewPrefixedDivisions(int bitsPerDigit, Integer networkPrefixLength, PrefixedGroupingCreator<S> groupingCreator, IntFunction<S[]> groupingArrayCreator) {
		if(bitsPerDigit >= Integer.SIZE) {
			//keep in mind once you hit 5 bits per digit, radix 32, you need 32 different digits, and there are only 26 alphabet characters and 10 digit chars, so 36
			//so once you get higher than that, you need a new character set.
			//AddressLargeDivision allows all the way up to base 85
			throw new IllegalArgumentException();
		}
		int bitCount = getBitCount();
		List<Integer> bitDivs = new ArrayList<Integer>(bitsPerDigit);
		//ipv6 octal:
		//seg bit counts: 63, 63, 2
		//ipv4 octal:
		//seg bit counts: 30, 2
		int largestBitCount = Long.SIZE - 1;
		largestBitCount -= largestBitCount % bitsPerDigit;
		do {
			if(bitCount <= largestBitCount) {
				int mod = bitCount % bitsPerDigit;
				int secondLast = bitCount - mod;
				if(secondLast > 0) {
					bitDivs.add(secondLast);
				}
				if(mod > 0) {
					bitDivs.add(mod);
				}
				break;
			} else {
				bitCount -= largestBitCount;
				bitDivs.add(largestBitCount);
			}
		} while(true);
		int bitDivSize = bitDivs.size();
		S divs[] = groupingArrayCreator.apply(bitDivSize);
		int currentSegmentIndex = 0;
		AddressDivision seg = getDivision(currentSegmentIndex);
		long segLowerVal = seg.getLowerValue();
		long segUpperVal = seg.getUpperValue();
		int segBits = seg.getBitCount();
		int bitsSoFar = 0;
		int radix = AddressDivisionBase.getRadixPower(BigInteger.valueOf(2), bitsPerDigit).intValue();
		//fill up our new divisions, one by one
		for(int i = bitDivSize - 1; i >= 0; i--) {
			int originalDivBitSize, divBitSize;
			originalDivBitSize = divBitSize = bitDivs.get(i);
			long divLowerValue, divUpperValue;
			divLowerValue = divUpperValue = 0;
			while(true) {
				if(segBits >= divBitSize) {
					int diff = segBits - divBitSize;
					divLowerValue |= segLowerVal >>> diff;
					int shift = ~(~0 << diff);
					segLowerVal &= shift;
					divUpperValue |= segUpperVal >>> diff;
					segUpperVal &= shift;
					segBits = diff;
					Integer segPrefixBits = networkPrefixLength == null ? null : getSegmentPrefixLength(originalDivBitSize, networkPrefixLength - bitsSoFar);
					S div = groupingCreator.createDivision(divLowerValue, divUpperValue, originalDivBitSize, radix, segPrefixBits);
					divs[bitDivSize - i - 1] = div;
					if(segBits == 0 && i > 0) {
						//get next seg
						seg = getDivision(++currentSegmentIndex);
						segLowerVal = seg.getLowerValue();
						segUpperVal = seg.getUpperValue();
						segBits = seg.getBitCount();
					}
					break;
				} else {
					int diff = divBitSize - segBits;
					divLowerValue |= segLowerVal << diff;
					divUpperValue |= segUpperVal << diff;
					divBitSize = diff;
					
					//get next seg
					seg = getDivision(++currentSegmentIndex);
					segLowerVal = seg.getLowerValue();
					segUpperVal = seg.getUpperValue();
					segBits = seg.getBitCount();
				}
			}
			bitsSoFar += originalDivBitSize;
		}
		return divs;
	}
	
	protected static <T extends Address, R extends AddressSection, S extends AddressSegment> Iterator<R> iterator(
			boolean useOriginal,
			R original,
			AddressCreator<T, R, ?, S> creator,
			Iterator<S[]> iterator
			) {
		if(useOriginal) {
			return new Iterator<R>() {
				R orig = original;
				
				@Override
				public R next() {
					if(!iterator.hasNext()) {
			    		throw new NoSuchElementException();
			    	}
					R result = orig;
			    	orig = null;
				    return result;
			    }

				@Override
				public boolean hasNext() {
					return orig != null;
				}

			    @Override
				public void remove() {
			    	throw new UnsupportedOperationException();
			    }
			};
		}
		return new Iterator<R>() {
			@Override
			public R next() {
				if(!iterator.hasNext()) {
		    		throw new NoSuchElementException();
		    	}
				S next[] = iterator.next();
		    	return creator.createSectionInternal(next);
		    }

			@Override
			public boolean hasNext() {
				return iterator.hasNext();
			}

		    @Override
			public void remove() {
		    	throw new UnsupportedOperationException();
		    }
		};
	}

	protected <S extends AddressSegment> Iterator<S[]> iterator(
			AddressSegmentCreator<S> segmentCreator,
			Supplier<S[]> segSupplier,
			IntFunction<Iterator<S>> segIteratorProducer) {
		if(!isMultiple()) {
			return new Iterator<S[]>() {
				boolean done;
				
				@Override
				public boolean hasNext() {
					return !done;
				}

			    @Override
				public S[] next() {
			    	if(done) {
			    		throw new NoSuchElementException();
			    	}
			    	done = true;
			    	return segSupplier.get();
			    }

			    @Override
				public void remove() {
			    	throw new UnsupportedOperationException();
			    }
			};
		}
		return new Iterator<S[]>() {
			private boolean done;
			final int segmentCount = getDivisionCount();
			
			@SuppressWarnings("unchecked")
			private final Iterator<S> variations[] = new Iterator[segmentCount];
			
			private S nextSet[] = segmentCreator.createSegmentArray(segmentCount);  {
				updateVariations(0);
			}
			
			private void updateVariations(int start) {
				for(int i = start; i < segmentCount; i++) {
					variations[i] = segIteratorProducer.apply(i);
					nextSet[i] = variations[i].next();
				}
			}
			
			@Override
			public boolean hasNext() {
				return !done;
			}
			
		    @Override
			public S[] next() {
		    	if(done) {
		    		throw new NoSuchElementException();
		    	}
		    	S segs[] = nextSet.clone();
		    	increment();
		    	return segs;
		    }
		    
		    private void increment() {
		    	for(int j = segmentCount - 1; j >= 0; j--) {
		    		if(variations[j].hasNext()) {
		    			nextSet[j] = variations[j].next();
		    			updateVariations(j + 1);
		    			return;
		    		}
		    	}
		    	done = true;
		    }

		    @Override
			public void remove() {
		    	throw new UnsupportedOperationException();
		    }
		};
	}

	protected static <T extends Address, S extends AddressSegment> Iterator<T> iterator(
			T original,
			AddressCreator<T, ?, ?, S> creator,
			boolean useOriginal,
			Iterator<S[]> iterator) {
		if(useOriginal) {
			return new Iterator<T>() {
				T orig = original;
				
				@Override
				public boolean hasNext() {
					return orig != null;
				}
			
			    @Override
				public T next() {
			    	if(!hasNext()) {
			    		throw new NoSuchElementException();
			    	}
			    	T result = orig;
			    	orig = null;
			    	return result;
			    }
			
			    @Override
				public void remove() {
			    	throw new UnsupportedOperationException();
			    }
			};
		}
		return new Iterator<T>() {
			
			@Override
			public boolean hasNext() {
				return iterator.hasNext();
			}
		
		    @Override
			public T next() {
		    	if(!hasNext()) {
		    		throw new NoSuchElementException();
		    	}
		    	S[] next = iterator.next();
		    	return creator.createAddressInternal(next); /* address creation */
		    }
		
		    @Override
			public void remove() {
		    	throw new UnsupportedOperationException();
		    }
		};
	}
	
	protected static <R extends AddressSection, S extends AddressSegment> R getSection(
			int index,
			int endIndex,
			R section,
			AddressCreator<?, R, ?, S> creator) {
		if(index == 0 && endIndex == section.getSegmentCount()) {
			return section;
		}
		int segmentCount = endIndex - index;
		if(segmentCount < 0) {
			throw new IndexOutOfBoundsException();
		}
		S segs[] = creator.createSegmentArray(segmentCount);
		section.getSegments(index, endIndex, segs, 0);
		return creator.createSectionInternal(segs);
	}
	
	protected static <R extends AddressSection, S extends AddressSegment> R append(
			R section,
			R other,
			AddressCreator<?, R, ?, S> creator,
			boolean extendPrefix) {
		int otherSegmentCount = other.getSegmentCount();
		int segmentCount = section.getSegmentCount();
		int totalSegmentCount = segmentCount + otherSegmentCount;
		S segs[] = creator.createSegmentArray(totalSegmentCount);
		section.getSegments(0, segmentCount, segs, 0);
		if(extendPrefix && section.isPrefixed()) {
			S allSegment = creator.createSegment(0, 0);
			for(int i = segmentCount; i < totalSegmentCount; i++) {
				segs[i] = allSegment;
			}
		} else {
			other.getSegments(0, otherSegmentCount, segs, segmentCount);
		}
		return creator.createSectionInternal(segs);
	}
	
	protected static <R extends AddressSection, S extends AddressSegment> R replace(
			R section,
			R other,
			AddressCreator<?, R, ?, S> creator,
			int index,
			boolean extendPrefix) {
		int otherSegmentCount = other.getSegmentCount();
		int segmentCount = section.getSegmentCount();
		if(index + otherSegmentCount > segmentCount) {
			throw new AddressTypeException(section, other, "ipaddress.error.exceeds.size");
		}
		if(otherSegmentCount == 0) {
			return section;
		}
		S segs[] = creator.createSegmentArray(segmentCount);
		section.getSegments(0, index, segs, 0);
		other.getSegments(0, otherSegmentCount, segs, index);
		if(segmentCount > index + otherSegmentCount) {
			if(extendPrefix && other.isPrefixed()) {
				S allSegment = creator.createSegment(0, 0);
				for(int i = index + otherSegmentCount; i < segmentCount; i++) {
					segs[i] = allSegment;
				}
			} else {
				section.getSegments(index + otherSegmentCount, segmentCount, segs, index + otherSegmentCount);
			}
		}
		return creator.createSectionInternal(segs);
	}
	
	protected static <R extends AddressSection, S extends AddressSegment> R createSectionInternal(AddressCreator<?, R, ?, S> creator, S[] segments, int startIndex, boolean extended) {
		return creator.createSectionInternal(segments, startIndex, extended);
	}
	
	protected static AddressDivisionWriter getCachedParams(StringOptions opts) {
		return opts.cachedParams;
	}
	
	protected static void setCachedParams(StringOptions opts, AddressDivisionWriter cachedParams) {
		opts.cachedParams = cachedParams;
	}
	
	protected boolean isDualString() {
		int count = getDivisionCount();
		for(int i = 0; i < count; i++) {
			AddressDivision division = getDivision(i);
			if(division.isMultiple()) {
				//at this point we know we will return true, but we determine now if we must throw AddressTypeException
				boolean isLastFull = true;
				AddressDivision lastDivision = null;
				for(int j = count - 1; j >= 0; j--) {
					division = getDivision(j);
					if(division.isMultiple()) {
						if(!isLastFull) {
							throw new AddressTypeException(division, i, lastDivision, i + 1, "ipaddress.error.segmentMismatch");
						}
						isLastFull = division.isFullRange();
					} else {
						isLastFull = false;
					}
					lastDivision = division;
				}
				return true;
			}
		}
		return false;
	}
	
	
	protected static <T extends AddressStringDivisionSeries> String toNormalizedStringRange(AddressStringParams<T> params, T lower, T upper, CharSequence zone) {
		int length = params.getStringLength(lower, zone) + params.getStringLength(upper, zone);
		StringBuilder builder;
		String separator = params.getWildcards().rangeSeparator;
		if(separator != null) {
			length += separator.length();
			builder = new StringBuilder(length);
			params.append(params.append(builder, lower, zone).append(separator), upper, zone);
		} else {
			builder = new StringBuilder(length);
			params.append(params.append(builder, lower, zone), upper, zone);
		}
		AddressStringParams.checkLengths(length, builder);
		return builder.toString();
	}
	
	protected static class AddressStringParams<T extends AddressStringDivisionSeries> implements AddressDivisionWriter, AddressSegmentParams, Cloneable {
		public static final Wildcards DEFAULT_WILDCARDS = new Wildcards();
		
		private Wildcards wildcards = DEFAULT_WILDCARDS;
		
		protected boolean expandSegments; //whether to expand 1 to 001 for IPv4 or 0001 for IPv6
		
		private String segmentStrPrefix; //eg for inet_aton style there is 0x for hex, 0 for octal

		private int radix;
		
		//the segment separator and in the case of split digits, the digit separator
		protected Character separator;
				
		private boolean uppercase; //whether to print A or a
		
		//print the segments in reverse, and in the case of splitDigits, print the digits in reverse as well
		private boolean reverse;
				
		//in each segment, split the digits with the separator, so that 123.456.1.1 becomes 1.2.3.4.5.6.1.1
		private boolean splitDigits;
		
		private String addressLabel = "";
		
		//private CharSequence zone;
		
		private char zoneSeparator;
		
		public AddressStringParams(int radix, Character separator, boolean uppercase) {
			this(radix, separator, uppercase, (char) 0);
		}
		
		public AddressStringParams(int radix, Character separator, boolean uppercase, char zoneSeparator) {
			this.radix = radix;
			this.separator = separator;
			this.uppercase = uppercase;
			this.zoneSeparator  = zoneSeparator;
		}

		public void setZoneSeparator(char zoneSeparator) {
			this.zoneSeparator = zoneSeparator;
		}
		
		public String getAddressLabel() {
			return addressLabel;
		}
		
		public void setAddressLabel(String str) {
			this.addressLabel = str;
		}
		
		public Character getSeparator() {
			return separator;
		}
		
		public void setSeparator(Character separator) {
			this.separator = separator;
		}
		
		@Override
		public Wildcards getWildcards() {
			return wildcards;
		}
		
		public void setWildcards(Wildcards wc) {
			wildcards = wc;
		}
		
		@Override
		public boolean preferWildcards() {
			return true;
		}
		
		//returns -1 to expand
		@Override
		public int getLeadingZeros(int segmentIndex) {
			if(expandSegments) {
				return -1;
			}
			return 0;
		}
		
		@Override
		public String getSegmentStrPrefix() {
			return segmentStrPrefix;
		}
		
		public void setSegmentStrPrefix(String segmentStrPrefix) {
			this.segmentStrPrefix = segmentStrPrefix;
		}
		
		@Override
		public int getRadix() {
			return radix;
		}
		
		public void setRadix(int radix) {
			this.radix = radix;
		}
		
		public void setUppercase(boolean uppercase) {
			this.uppercase = uppercase;
		}
		
		@Override
		public boolean isUppercase() {
			return uppercase;
		}
		
		public void setSplitDigits(boolean split) {
			this.splitDigits = split;
		}
		
		@Override
		public boolean isSplitDigits() {
			return splitDigits;
		}
		
		@Override
		public Character getSplitDigitSeparator() {
			return separator;
		}
		
		@Override
		public boolean isReverseSplitDigits() {
			return reverse;
		}
		
		public void setReverse(boolean rev) {
			this.reverse = rev;
		}
		
		public boolean isReverse() {
			return reverse;
		}
		
		public void expandSegments(boolean expand) {
			expandSegments = expand;
		}
		
		public StringBuilder appendLabel(StringBuilder builder) {
			String str = getAddressLabel();
			if(str != null) {
				builder.append(str);
			}
			return builder;
		}
		
		public int getAddressLabelLength() {
			String str = getAddressLabel();
			if(str != null) {
				return str.length();
			}
			return 0;
		}
		
		public int getSegmentsStringLength(T part) {
			int count = 0;
			if(part.getDivisionCount() != 0) {
				int divCount = part.getDivisionCount();
				for(int i = 0; i < divCount; i++) {
					AddressStringDivision seg = part.getDivision(i);
					count += appendSegment(i, seg, null);
				}
				Character separator = getSeparator();
				if(separator != null) {
					count += divCount - 1;
				}
			}
			return count;
		}

		public StringBuilder appendSegments(StringBuilder builder, T part) {
			if(part.getDivisionCount() != 0) {
				int count = part.getDivisionCount();
				boolean reverse = isReverse();
				int i = 0;
				Character separator = getSeparator();
				while(true) {
					int segIndex = reverse ? (count - i - 1) : i;
					AddressStringDivision seg = part.getDivision(segIndex);
					appendSegment(segIndex, seg, builder);
					if(++i == count) {
						break;
					}
					if(separator != null) {
						builder.append(separator);
					}
				}
			}
			return builder;
		}
		
		public int appendSingleDivision(AddressStringDivision seg, StringBuilder builder) {
			if(builder == null) {
				return getAddressLabelLength() + appendSegment(0, seg, null);
			}
			appendLabel(builder);
			appendSegment(0, seg, builder);
			return 0;
		}
		
		protected int appendSegment(int segIndex, AddressStringDivision seg, StringBuilder builder) {
			return seg.getConfiguredString(segIndex, this, builder);
		}
		
		public int getZoneLength(CharSequence zone) {
			if(zone != null && zone.length() > 0) {
				return zone.length() + 1; /* zone separator is one char */
			}
			return 0;
		}
		
		public int getStringLength(T addr, CharSequence zone) {
			int result = getStringLength(addr);
			if(zone != null) {
				result += getZoneLength(zone);
			}
			return result;
		}
		
		public int getStringLength(T addr) {
			return getAddressLabelLength() + getSegmentsStringLength(addr);
		}
		
		public StringBuilder appendZone(StringBuilder builder, CharSequence zone) {
			if(zone != null && zone.length() > 0) {
				builder.append(zoneSeparator).append(zone);
			}
			return builder;
		}

		public StringBuilder append(StringBuilder builder, T addr, CharSequence zone) {
			appendSegments(appendLabel(builder), addr);
			if(zone != null) {
				appendZone(builder, zone);
			}
			return builder;
		}
		
		public StringBuilder append(StringBuilder builder, T addr) {
			return append(builder, addr, null);
		}
		
		@Override
		public int getDivisionStringLength(AddressStringDivision seg) {
			return appendSingleDivision(seg, null);
		}
		
		@Override
		public StringBuilder appendDivision(StringBuilder builder, AddressStringDivision seg) {
			appendSingleDivision(seg, builder);
			return builder;
		}

		public String toString(T addr, CharSequence zone) {	
			int length = getStringLength(addr, zone);
			StringBuilder builder = new StringBuilder(length);
			append(builder, addr, zone);
			checkLengths(length, builder);
			return builder.toString();
		}
		
		public String toString(T addr) {	
			return toString(addr, null);
		}
		
		public static void checkLengths(int length, StringBuilder builder) {
			//Note- re-enable this when doing development
//			boolean calcMatch = length == builder.length();
//			boolean capMatch = length == builder.capacity();
//			if(!calcMatch || !capMatch) {
//				System.out.println(builder);//000a:0000:000c:000d:000e:000f:0001-1:0000/112 instead of 000a:0000:000c:000d:000e:000f:0001:0000/112
//				throw new IllegalStateException("length is " + builder.length() + ", capacity is " + builder.capacity() + ", expected length is " + length);
//			}
		}

		public static AddressStringParams<AddressStringDivisionSeries> toParams(StringOptions opts) {
			//since the params here are not dependent on the section, we could cache the params in the options 
			//this is not true on the IPv6 side where compression settings change based on the section
			@SuppressWarnings("unchecked")
			AddressStringParams<AddressStringDivisionSeries> result = (AddressStringParams<AddressStringDivisionSeries>) getCachedParams(opts);
			if(result == null) {
				result = new AddressStringParams<AddressStringDivisionSeries>(opts.base, opts.separator, opts.uppercase);
				result.expandSegments(opts.expandSegments);
				result.setWildcards(opts.wildcards);
				result.setSegmentStrPrefix(opts.segmentStrPrefix);
				result.setAddressLabel(opts.addrLabel);
				result.setReverse(opts.reverse);
				result.setSplitDigits(opts.splitDigits);
				setCachedParams(opts, result);
			}
			return result;
		}
		
		@Override
		public AddressStringParams<T> clone() {
			try {
				@SuppressWarnings("unchecked")
				AddressStringParams<T> parms = (AddressStringParams<T>) super.clone();
				return parms;
			} catch(CloneNotSupportedException e) {
				 return null;
			}
		}
	}
	
	
	
	/**
	 * Represents a clear way to create a specific type of string.
	 * 
	 * @author sfoley
	 */
	public static class StringOptions {
		
		public static class Wildcards {
			public final String rangeSeparator;//cannot be null
			public final String wildcard;//can be null
			public final String singleWildcard;//can be null
			
			public Wildcards() {
				this(Address.RANGE_SEPARATOR_STR, Address.SEGMENT_WILDCARD_STR, null);
			}
			
			public Wildcards(String wildcard, String singleWildcard) {
				this(Address.RANGE_SEPARATOR_STR, wildcard, singleWildcard);
			}
			
			public Wildcards(String rangeSeparator) {
				this(rangeSeparator, null, null);
			}
			
			public Wildcards(String rangeSeparator, String wildcard, String singleWildcard) {
				if(rangeSeparator == null) {
					rangeSeparator = Address.RANGE_SEPARATOR_STR;
				}
				this.rangeSeparator = rangeSeparator;
				this.wildcard = wildcard;
				this.singleWildcard = singleWildcard;
			}
			
			@Override
			public String toString() {
				return "range separator: " + rangeSeparator + "\nwildcard: " + wildcard + "\nsingle wildcard: " + singleWildcard;
			}
		}
		
		public final Wildcards wildcards;
		public final boolean expandSegments;
		public final int base;
		public final String segmentStrPrefix;
		public final Character separator;
		public final String addrLabel;
		public final boolean reverse;
		public final boolean splitDigits;
		public final boolean uppercase;
		
		//use this field if the options to params conversion is not dependent on the address part so it can be reused
		AddressDivisionWriter cachedParams; 
		
		protected StringOptions(
				int base,
				boolean expandSegments,
				Wildcards wildcards,
				String segmentStrPrefix,
				Character separator,
				String label,
				boolean reverse,
				boolean splitDigits,
				boolean uppercase) {
			this.expandSegments = expandSegments;
			this.wildcards = wildcards;
			this.base = base;
			this.segmentStrPrefix = segmentStrPrefix;
			this.separator = separator;
			this.addrLabel = label;
			this.reverse = reverse;
			this.splitDigits = splitDigits;
			this.uppercase = uppercase;
		}
		
		public static class Builder {
			
			public static final Wildcards DEFAULT_WILDCARDS = new Wildcards();
		
			protected Wildcards wildcards = DEFAULT_WILDCARDS;
			protected boolean expandSegments;
			protected int base;
			protected String segmentStrPrefix;
			protected Character separator;
			protected String addrLabel;
			protected boolean reverse;
			protected boolean splitDigits;
			protected boolean uppercase;
			
			protected Builder(int base, char separator) {
				this.base = base;
				this.separator = separator;
			}
			
			public Builder setWildcards(Wildcards wildcards) {
				this.wildcards = wildcards;
				return this;
			}
			
			public Builder setReverse(boolean reverse) {
				this.reverse = reverse;
				return this;
			}
			
			public Builder setUppercase(boolean uppercase) {
				this.uppercase = uppercase;
				return this;
			}
			public Builder setSplitDigits(boolean splitDigits) {
				this.splitDigits = splitDigits;
				return this;
			}
			
			public Builder setExpandedSegments(boolean expandSegments) {
				this.expandSegments = expandSegments;
				return this;
			}
			
			public Builder setRadix(int base) {
				this.base = base;
				return this;
			}
			
			/*
			 * separates the divisions of the address, typically ':' or '.', but also can be null for no separator
			 */
			public Builder setSeparator(Character separator) {
				this.separator = separator;
				return this;
			}
			
			public Builder setAddressLabel(String label) {
				this.addrLabel = label;
				return this;
			}
			
			public Builder setSegmentStrPrefix(String prefix) {
				this.segmentStrPrefix = prefix;
				return this;
			}
			
			public StringOptions toParams() {
				return new StringOptions(base, expandSegments, wildcards, segmentStrPrefix, separator, addrLabel, reverse, splitDigits, uppercase);
			}
		}
	}
}
