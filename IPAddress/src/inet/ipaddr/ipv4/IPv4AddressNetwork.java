package inet.ipaddr.ipv4;

import inet.ipaddr.IPAddressTypeNetwork;
import inet.ipaddr.IPAddress.IPVersion;

/**
 * 
 * @author sfoley
 */
public class IPv4AddressNetwork extends IPAddressTypeNetwork<IPv4Address> {
	
	IPv4AddressNetwork() {
		super(IPv4Address.class);
	}
	
	public static class IPv4AddressCreator extends IPAddressCreator<IPv4Address, IPv4AddressSection, IPv4AddressSegment> {
		static boolean CACHE_SEGMENTS_BY_PREFIX = true;
		
		IPv4AddressSegment emptySegments[] = {};
		IPv4AddressSection emptySection[] = {};
		
		private static IPv4AddressSegment segmentCache[] = new IPv4AddressSegment[IPv4Address.MAX_VALUE_PER_SEGMENT + 1];
		private static IPv4AddressSegment segmentPrefixCache[][]; 
		private static IPv4AddressSegment allPrefixedCache[] = new IPv4AddressSegment[IPv4Address.BITS_PER_SEGMENT];
		
		static {
			if(CACHE_SEGMENTS_BY_PREFIX) {
				segmentPrefixCache = new IPv4AddressSegment[IPv4Address.BITS_PER_SEGMENT][];
				for(int i = 0, digits = 2; i < segmentPrefixCache.length; i++, digits <<= 1) {
					segmentPrefixCache[i] = new IPv4AddressSegment[digits];
				}
			}
		}
		
		@Override
		protected IPv4Address createAddressInternal(IPv4AddressSection section, String zone) {
			if(zone != null) {
				throw new IllegalArgumentException();
			}
			return createAddress(section);
		}
		
		@Override
		protected IPv4Address createAddressInternal(IPv4AddressSegment segments[]) {
			return createAddress(createSectionInternal(segments));
		}
		
		@Override
		protected IPv4AddressSection createSectionInternal(IPv4AddressSegment segments[]) {
			return new IPv4AddressSection(segments, false);
		}
		
		@Override
		protected IPv4AddressSection createSectionInternal(IPv4AddressSegment segments[], IPv4AddressSection mixedSection) {
			return mixedSection;
		}
		
		@Override
		protected IPv4AddressSection createSectionInternal(byte[] bytes, Integer prefix) {
			return new IPv4AddressSection(bytes, prefix, false);
		}

		@Override
		protected IPv4AddressSection createSectionInternal(byte[] bytes, String zone) {
			if(zone != null) {
				throw new IllegalArgumentException();
			}
			return createSectionInternal(bytes);
		}

		@Override
		protected IPv4AddressSection createSectionInternal(byte[] bytes) {
			return new IPv4AddressSection(bytes, null, false);
		}
		
		@Override
		public IPv4Address createAddress(IPv4AddressSection section) {
			return new IPv4Address(section);
		}
		
		public IPv4AddressSection createSection(byte bytes[], Integer prefix) {
			return new IPv4AddressSection(bytes, prefix);
		}
		
		public IPv4AddressSection createSection(IPv4AddressSegment segments[], Integer networkPrefixLength) {
			return new IPv4AddressSection(segments, networkPrefixLength);
		}
		
		public IPv4AddressSection createSection(IPv4AddressSegment segments[]) {
			return new IPv4AddressSection(segments);
		}
		
		@Override
		protected IPv4AddressSection[] createAddressSectionArray(int length) {
			if(length == 0) {
				return emptySection;
			}
			return new IPv4AddressSection[length];
		}
		
		@Override
		public IPv4AddressSegment[] createAddressSegmentArray(int length) {
			if(length == 0) {
				return emptySegments;
			}
			return new IPv4AddressSegment[length];
		}
		
		@Override
		public IPv4AddressSegment createAddressSegment(int value) {
			IPv4AddressSegment result = segmentCache[value];
			if(result == null) {
				segmentCache[value] = result = new IPv4AddressSegment(value);
			}
			return result;
		}
		
		@Override
		public IPv4AddressSegment createAddressSegment(int value, Integer segmentPrefixLength) {
			if(segmentPrefixLength == null) {
				return createAddressSegment(value);
			}
			if(segmentPrefixLength == 0) {
				return IPv4AddressSegment.ZERO_PREFIX_SEGMENT;
			}
			if(CACHE_SEGMENTS_BY_PREFIX) {
				int bitsPerSegment = IPv4Address.BITS_PER_SEGMENT;
				if(segmentPrefixLength > bitsPerSegment) {
					segmentPrefixLength = bitsPerSegment;
				}
				int mask = IPv4Address.network().getSegmentNetworkMask(segmentPrefixLength);
				value &= mask;
				int prefixIndex = segmentPrefixLength - 1;
				int valueIndex = value >>> (bitsPerSegment - segmentPrefixLength);
				IPv4AddressSegment cache[][] = segmentPrefixCache;
				IPv4AddressSegment result = cache[prefixIndex][valueIndex];
				if(result == null) {
					cache[prefixIndex][valueIndex] = result = new IPv4AddressSegment(value, segmentPrefixLength);
				}
				return result;
			}
			IPv4AddressSegment result = new IPv4AddressSegment(value, segmentPrefixLength);
			return result;
		}
		
		@Override
		public IPv4AddressSegment createAddressSegment(int lower, int upper, Integer segmentPrefixLength) {
			if(segmentPrefixLength == null) {
				if(lower == upper) {
					return createAddressSegment(lower);
				}
				if(lower == 0 && upper == IPv4Address.MAX_VALUE_PER_SEGMENT) {
					return IPv4AddressSegment.ALL_RANGE_SEGMENT;
				}
			} else {
				if(segmentPrefixLength == 0) {
					return createAddressSegment(0, 0);
				}
				if(CACHE_SEGMENTS_BY_PREFIX) {
					int mask = IPv4Address.network().getSegmentNetworkMask(segmentPrefixLength);
					lower &= mask;
					if((upper & mask) == lower) {
						return createAddressSegment(lower, segmentPrefixLength);
					}
					if(lower == 0 && upper == mask) {
						//cache */26 type segments
						int prefixIndex = segmentPrefixLength - 1;
						IPv4AddressSegment cache[] = allPrefixedCache;
						IPv4AddressSegment result = cache[prefixIndex];
						if(result == null) {
							cache[prefixIndex] = result = new IPv4AddressSegment(0, IPv4Address.MAX_VALUE_PER_SEGMENT, segmentPrefixLength);
						}
						return result;
					}
				}
			}
			IPv4AddressSegment result = new IPv4AddressSegment(lower, upper, segmentPrefixLength);
			return result;
		}
		
	}
	
	@SuppressWarnings("unchecked")
	@Override
	protected IPv4AddressCreator createAddressCreator() {
		return new IPv4AddressCreator();
	}
	 
	@Override
	protected IPv4Address createLoopback() {
		IPv4AddressCreator creator = getAddressCreator();
		IPv4AddressSegment zero = IPv4AddressSegment.ZERO_SEGMENT;
		IPv4AddressSegment segs[] = creator.createAddressSegmentArray(IPv4Address.SEGMENT_COUNT);
		segs[0] = creator.createAddressSegment(127);
		segs[1] = segs[2] = zero;
		segs[3] = creator.createAddressSegment(1);
		return creator.createAddressInternal(segs);
	}
	
	@Override
	public IPv4AddressCreator getAddressCreator() {
		return (IPv4AddressCreator) super.getAddressCreator();
	}
	
	@Override
	public boolean isIPv4() {
		return true;
	}
	
	@Override
	public IPVersion getIPVersion() {
		return IPVersion.IPV4;
	}
}
