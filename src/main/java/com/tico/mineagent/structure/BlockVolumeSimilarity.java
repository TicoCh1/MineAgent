package com.tico.mineagent.structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class BlockVolumeSimilarity {
	public static final int DEFAULT_CANDIDATE_OFFSETS_PER_TRANSFORM = 256;
	private static final int MAX_VOTE_PAIRS_PER_SIGNATURE = 65_536;
	private static final int MAX_VOTE_PAIRS_PER_TRANSFORM = 2_000_000;

	private BlockVolumeSimilarity() {
	}

	public static Result compare(Volume first, Volume second, Options options) {
		long started = System.nanoTime();
		List<Transform> transforms = Transform.transforms(options.includeRotations(), options.includeMirrors());
		PriorityQueue<Match> best = new PriorityQueue<>(matchComparator().reversed());
		int transformsChecked = 0;
		int offsetsEvaluated = 0;
		long votePairs = 0L;

		for (Transform transform : transforms) {
			transformsChecked++;
			List<TransformedVoxel> transformed = transform(second.voxels(), transform);
			Map<Long, Integer> votes = voteOffsets(first, transformed, options.maxOffset());
			long transformVotePairs = votePairCount(first, transformed);
			votePairs += transformVotePairs;
			for (long offsetKey : topOffsets(votes, options.candidateOffsetsPerTransform())) {
				Offset offset = Offset.unpack(offsetKey);
				Match match = score(first, transformed, transform, offset);
				offsetsEvaluated++;
				if (best.size() < options.maxResults()) {
					best.add(match);
				} else if (matchComparator().compare(match, best.peek()) < 0) {
					best.poll();
					best.add(match);
				}
			}
		}

		List<Match> matches = new ArrayList<>(best);
		matches.sort(matchComparator());
		return new Result(
				first.voxels().size(),
				second.voxels().size(),
				transformsChecked,
				offsetsEvaluated,
				votePairs,
				(System.nanoTime() - started) / 1_000_000.0D,
				List.copyOf(matches));
	}

	private static List<TransformedVoxel> transform(List<Voxel> voxels, Transform transform) {
		List<TransformedVoxel> transformed = new ArrayList<>(voxels.size());
		int transformIndex = transform.index();
		for (Voxel voxel : voxels) {
			Pos pos = transform.apply(voxel.x(), voxel.y(), voxel.z());
			transformed.add(new TransformedVoxel(pos.x(), pos.y(), pos.z(), voxel.signature(transformIndex)));
		}
		return transformed;
	}

	private static Map<Long, Integer> voteOffsets(Volume first, List<TransformedVoxel> transformed, int maxOffset) {
		Map<Long, Integer> votes = new HashMap<>();
		long usedPairs = 0L;
		for (Map.Entry<String, List<TransformedVoxel>> entry : bySignature(transformed).entrySet()) {
			List<Voxel> firstVoxels = first.bySignature().get(entry.getKey());
			if (firstVoxels == null || firstVoxels.isEmpty()) {
				continue;
			}
			List<TransformedVoxel> secondVoxels = entry.getValue();
			long pairs = (long) firstVoxels.size() * secondVoxels.size();
			if (pairs > MAX_VOTE_PAIRS_PER_SIGNATURE) {
				pairs = MAX_VOTE_PAIRS_PER_SIGNATURE;
			}
			if (usedPairs + pairs > MAX_VOTE_PAIRS_PER_TRANSFORM) {
				pairs = Math.max(0L, MAX_VOTE_PAIRS_PER_TRANSFORM - usedPairs);
			}
			if (pairs <= 0L) {
				break;
			}
			usedPairs += pairs;

			int firstStep = stride(firstVoxels.size(), (int) Math.sqrt(pairs));
			int secondLimit = Math.max(1, (int) (pairs / Math.max(1, (firstVoxels.size() + firstStep - 1) / firstStep)));
			int secondStep = stride(secondVoxels.size(), secondLimit);
			for (int i = 0; i < firstVoxels.size(); i += firstStep) {
				Voxel a = firstVoxels.get(i);
				for (int j = 0; j < secondVoxels.size(); j += secondStep) {
					TransformedVoxel b = secondVoxels.get(j);
					int dx = a.x() - b.x();
					int dy = a.y() - b.y();
					int dz = a.z() - b.z();
					if (Math.abs(dx) <= maxOffset && Math.abs(dy) <= maxOffset && Math.abs(dz) <= maxOffset) {
						votes.merge(Offset.pack(dx, dy, dz), 1, Integer::sum);
					}
				}
			}
		}
		votes.putIfAbsent(Offset.pack(0, 0, 0), 0);
		return votes;
	}

	private static long votePairCount(Volume first, List<TransformedVoxel> transformed) {
		long pairs = 0L;
		for (Map.Entry<String, List<TransformedVoxel>> entry : bySignature(transformed).entrySet()) {
			List<Voxel> firstVoxels = first.bySignature().get(entry.getKey());
			if (firstVoxels != null) {
				pairs += (long) firstVoxels.size() * entry.getValue().size();
			}
		}
		return pairs;
	}

	private static Map<String, List<TransformedVoxel>> bySignature(List<TransformedVoxel> voxels) {
		Map<String, List<TransformedVoxel>> bySignature = new LinkedHashMap<>();
		for (TransformedVoxel voxel : voxels) {
			bySignature.computeIfAbsent(voxel.signature(), ignored -> new ArrayList<>()).add(voxel);
		}
		return bySignature;
	}

	private static int stride(int size, int requestedCount) {
		if (size <= 0 || requestedCount <= 0 || size <= requestedCount) {
			return 1;
		}
		return Math.max(1, size / requestedCount);
	}

	private static List<Long> topOffsets(Map<Long, Integer> votes, int limit) {
		List<Map.Entry<Long, Integer>> entries = new ArrayList<>(votes.entrySet());
		entries.sort(Map.Entry.<Long, Integer>comparingByValue().reversed());
		List<Long> offsets = new ArrayList<>();
		for (int i = 0; i < entries.size() && i < limit; i++) {
			offsets.add(entries.get(i).getKey());
		}
		return offsets;
	}

	private static Match score(Volume first, List<TransformedVoxel> transformed, Transform transform, Offset offset) {
		int match = 0;
		int mismatch = 0;
		int extra = 0;
		for (TransformedVoxel voxel : transformed) {
			long target = Pos.pack(voxel.x() + offset.dx(), voxel.y() + offset.dy(), voxel.z() + offset.dz());
			String firstSignature = first.map().get(target);
			if (firstSignature == null) {
				extra++;
			} else if (firstSignature.equals(voxel.signature())) {
				match++;
			} else {
				mismatch++;
			}
		}
		int missing = first.voxels().size() - match - mismatch;
		int union = match + mismatch + missing + extra;
		double score = union == 0 ? 1.0D : match / (double) union;
		int occupancyIntersection = match + mismatch;
		double occupancyScore = union == 0 ? 1.0D : occupancyIntersection / (double) union;
		double materialScore = occupancyIntersection == 0 ? (union == 0 ? 1.0D : 0.0D) : match / (double) occupancyIntersection;
		return new Match(transform, offset, score, occupancyScore, materialScore, match, mismatch, missing, extra, union);
	}

	private static Comparator<Match> matchComparator() {
		return Comparator
				.<Match>comparingDouble(match -> -match.score())
				.thenComparingInt(match -> match.mismatch())
				.thenComparingInt(match -> match.missing())
				.thenComparingInt(match -> match.extra())
				.thenComparing(match -> match.transform().id())
				.thenComparingInt(match -> Math.abs(match.offset().dx()) + Math.abs(match.offset().dy()) + Math.abs(match.offset().dz()));
	}

	public record Options(boolean includeRotations, boolean includeMirrors, int maxOffset, int maxResults, int candidateOffsetsPerTransform) {
		public Options {
			maxOffset = Math.max(0, Math.min(128, maxOffset));
			maxResults = Math.max(1, Math.min(20, maxResults));
			candidateOffsetsPerTransform = Math.max(1, Math.min(4096, candidateOffsetsPerTransform));
		}
	}

	public record Result(
			int firstNonAirCount,
			int secondNonAirCount,
			int transformsChecked,
			int offsetsEvaluated,
			long votePairs,
			double elapsedMillis,
			List<Match> matches) {
		public Result {
			matches = List.copyOf(matches);
		}

		public Match best() {
			return matches.isEmpty() ? null : matches.getFirst();
		}
	}

	public record Match(
			Transform transform,
			Offset offset,
			double score,
			double occupancyScore,
			double materialScore,
			int match,
			int mismatch,
			int missing,
			int extra,
			int union) {
	}

	public record Transform(int rotationDegrees, boolean mirrorX, boolean mirrorZ, int index) {
		public String id() {
			return "rot_y_" + rotationDegrees + (mirrorX ? "_mirror_x" : "") + (mirrorZ ? "_mirror_z" : "");
		}

		public Pos apply(int x, int y, int z) {
			int tx = mirrorX ? -x : x;
			int tz = mirrorZ ? -z : z;
			return switch (rotationDegrees) {
				case 90 -> new Pos(-tz, y, tx);
				case 180 -> new Pos(-tx, y, -tz);
				case 270 -> new Pos(tz, y, -tx);
				default -> new Pos(tx, y, tz);
			};
		}

		public static List<Transform> transforms(boolean includeRotations, boolean includeMirrors) {
			List<Transform> transforms = new ArrayList<>();
			int[] rotations = includeRotations ? new int[] { 0, 90, 180, 270 } : new int[] { 0 };
			boolean[] mirrors = includeMirrors ? new boolean[] { false, true } : new boolean[] { false };
			int index = 0;
			for (int rotation : rotations) {
				for (boolean mirrorX : mirrors) {
					for (boolean mirrorZ : mirrors) {
						transforms.add(new Transform(rotation, mirrorX, mirrorZ, index++));
					}
				}
			}
			return List.copyOf(transforms);
		}
	}

	public record Offset(int dx, int dy, int dz) {
		public static long pack(int dx, int dy, int dz) {
			return Pos.pack(dx, dy, dz);
		}

		public static Offset unpack(long packed) {
			return new Offset(Pos.unpackX(packed), Pos.unpackY(packed), Pos.unpackZ(packed));
		}
	}

	public record Pos(int x, int y, int z) {
		public static long pack(int x, int y, int z) {
			return ((long) (x & 0x1FFFFF) << 42)
					| ((long) (y & 0x1FFFFF) << 21)
					| (long) (z & 0x1FFFFF);
		}

		public static int unpackX(long packed) {
			return unpackSigned((int) (packed >> 42));
		}

		public static int unpackY(long packed) {
			return unpackSigned((int) ((packed >> 21) & 0x1FFFFF));
		}

		public static int unpackZ(long packed) {
			return unpackSigned((int) (packed & 0x1FFFFF));
		}

		private static int unpackSigned(int value) {
			return (value & 0x100000) == 0 ? value : value | ~0x1FFFFF;
		}
	}

	public record Voxel(int x, int y, int z, String signature, List<String> transformedSignatures) {
		public Voxel {
			if (transformedSignatures == null || transformedSignatures.isEmpty()) {
				transformedSignatures = List.of(signature);
			} else {
				transformedSignatures = List.copyOf(transformedSignatures);
			}
		}

		public String signature(int transformIndex) {
			if (transformIndex >= 0 && transformIndex < transformedSignatures.size()) {
				return transformedSignatures.get(transformIndex);
			}
			return signature;
		}
	}

	private record TransformedVoxel(int x, int y, int z, String signature) {
	}

	public static final class Volume {
		private final int sizeX;
		private final int sizeY;
		private final int sizeZ;
		private final List<Voxel> voxels;
		private final Map<Long, String> map;
		private final Map<String, List<Voxel>> bySignature;

		public Volume(int sizeX, int sizeY, int sizeZ, List<Voxel> voxels) {
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.sizeZ = sizeZ;
			this.voxels = List.copyOf(voxels);
			Map<Long, String> byPosition = new HashMap<>();
			Map<String, List<Voxel>> grouped = new LinkedHashMap<>();
			for (Voxel voxel : voxels) {
				byPosition.put(Pos.pack(voxel.x(), voxel.y(), voxel.z()), voxel.signature());
				grouped.computeIfAbsent(voxel.signature(), ignored -> new ArrayList<>()).add(voxel);
			}
			this.map = Map.copyOf(byPosition);
			Map<String, List<Voxel>> immutableGrouped = new LinkedHashMap<>();
			for (Map.Entry<String, List<Voxel>> entry : grouped.entrySet()) {
				immutableGrouped.put(entry.getKey(), List.copyOf(entry.getValue()));
			}
			this.bySignature = Map.copyOf(immutableGrouped);
		}

		public int sizeX() {
			return sizeX;
		}

		public int sizeY() {
			return sizeY;
		}

		public int sizeZ() {
			return sizeZ;
		}

		public List<Voxel> voxels() {
			return voxels;
		}

		private Map<Long, String> map() {
			return map;
		}

		private Map<String, List<Voxel>> bySignature() {
			return bySignature;
		}
	}
}
