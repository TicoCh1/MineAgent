package com.tico.mineagent.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StructureSequenceOptimizer {
	private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
	private static final Pattern SIMPLE_CONSTRAINT = Pattern.compile("^([A-Za-z][A-Za-z0-9_]*|-?\\d+)(<=|>=|<|>|==|=)([A-Za-z][A-Za-z0-9_]*|-?\\d+)$");
	private static final Pattern RANGE_CONSTRAINT = Pattern.compile("^(-?\\d+)(<=|<)([A-Za-z][A-Za-z0-9_]*)(<=|<)(-?\\d+)$");
	private static final Pattern REVERSED_RANGE_CONSTRAINT = Pattern.compile("^(-?\\d+)(>=|>)([A-Za-z][A-Za-z0-9_]*)(>=|>)(-?\\d+)$");
	private static final Pattern IN_CONSTRAINT = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_]*)\\s+(?:in|one_of)\\s*(?:\\[)?(.+?)(?:\\])?\\s*$", Pattern.CASE_INSENSITIVE);
	private static final int MAX_GREEDY_LEAVES = 200_000;

	private StructureSequenceOptimizer() {
	}

	public static JsonObject optimize(List<String> rawDefinitions, List<String> rawConstraints, String rawRoot, int requestedMaxResults, int requestedMaxUnboundedValue) {
		List<String> warnings = new ArrayList<>();
		int maxResults = Math.max(1, Math.min(20, requestedMaxResults));
		if (requestedMaxResults > 20) {
			warnings.add("max_results was capped at 20.");
		}
		int maxUnboundedValue = Math.max(1, Math.min(1024, requestedMaxUnboundedValue));
		if (requestedMaxUnboundedValue > 1024) {
			warnings.add("max_unbounded_value was capped at 1024 to keep greedy search bounded.");
		}

		Map<String, List<String>> definitions = parseDefinitions(rawDefinitions);
		Map<String, SymbolConstraint> constraints = parseConstraints(rawConstraints);
		String root = cleanName(rawRoot);

		Map<String, LinearExpression> expressions = new LinkedHashMap<>();
		Set<String> allNames = new LinkedHashSet<>();
		allNames.add(root);
		allNames.addAll(definitions.keySet());
		allNames.addAll(constraints.keySet());
		for (List<String> items : definitions.values()) {
			allNames.addAll(items);
		}

		for (String name : allNames) {
			expressions.put(name, expand(name, definitions, new HashSet<>(), new HashMap<>()));
		}

		Set<String> atoms = new LinkedHashSet<>();
		for (LinearExpression expression : expressions.values()) {
			atoms.addAll(expression.coefficients().keySet());
		}

		if (atoms.isEmpty()) {
			throw new IllegalArgumentException("At least one undefined atomic component is required; definitions cannot resolve to an empty sequence.");
		}

		Map<String, Domain> domains = inferDomains(atoms, expressions, constraints, maxUnboundedValue, warnings);
		ConstraintObjective objective = chooseObjective(root, constraints);
		SearchState state = new SearchState(maxResults, objective, expressions, constraints, domains);
		List<String> variables = searchOrder(atoms, domains, expressions.getOrDefault(objective.name(), expressions.get(root)));
		dfs(variables, 0, new LinkedHashMap<>(), state);

		List<Result> results = state.sortedResults();
		JsonObject output = new JsonObject();
		output.addProperty("root", root);
		output.addProperty("max_results", maxResults);
		output.addProperty("max_unbounded_value", maxUnboundedValue);
		output.addProperty("complete_search", !state.limitReached());
		output.addProperty("visited_assignments", state.visitedLeaves());
		output.addProperty("result_count", results.size());
		output.add("definitions", definitionsJson(definitions));
		output.add("atoms", strings(new ArrayList<>(atoms)));
		output.add("domains", domainsJson(domains));
		output.add("constraints", constraintsJson(constraints));
		output.add("objective", objectiveJson(objective));
		output.add("results", resultsJson(results, expressions, variables, objective));
		output.add("warnings", strings(warnings));
		return output;
	}

	private static Map<String, List<String>> parseDefinitions(List<String> rawDefinitions) {
		Map<String, List<String>> definitions = new LinkedHashMap<>();
		for (String rawDefinition : rawDefinitions) {
			String raw = rawDefinition == null ? "" : rawDefinition.trim();
			if (raw.isBlank()) {
				continue;
			}
			int equals = raw.indexOf('=');
			if (equals <= 0) {
				throw new IllegalArgumentException("Invalid sequence definition '" + raw + "'. Use name=[Item,OtherItem].");
			}
			String name = cleanName(raw.substring(0, equals));
			String body = raw.substring(equals + 1).trim();
			if (body.startsWith("[") && body.endsWith("]")) {
				body = body.substring(1, body.length() - 1);
			}
			List<String> items = new ArrayList<>();
			if (!body.isBlank()) {
				for (String rawItem : body.split(",")) {
					addTerm(items, rawItem);
				}
			}
			if (items.isEmpty()) {
				throw new IllegalArgumentException("Sequence definition '" + name + "' must contain at least one item.");
			}
			if (definitions.put(name, List.copyOf(items)) != null) {
				throw new IllegalArgumentException("Duplicate sequence definition: " + name + ".");
			}
		}
		return definitions;
	}

	private static Map<String, SymbolConstraint> parseConstraints(List<String> rawConstraints) {
		Map<String, SymbolConstraint> constraints = new LinkedHashMap<>();
		for (String rawConstraint : rawConstraints) {
			String raw = rawConstraint == null ? "" : rawConstraint.trim();
			if (raw.isBlank()) {
				continue;
			}
			String compact = raw.replaceAll("\\s+", "");
			if (parseInConstraint(raw, constraints)) {
				continue;
			}
			Matcher range = RANGE_CONSTRAINT.matcher(compact);
			if (range.matches()) {
				String name = cleanName(range.group(3));
				SymbolConstraint constraint = constraints.computeIfAbsent(name, SymbolConstraint::new);
				constraint.applyLower(Long.parseLong(range.group(1)), range.group(2).equals("<="), raw);
				constraint.applyUpper(Long.parseLong(range.group(5)), range.group(4).equals("<="), raw);
				continue;
			}
			Matcher reversedRange = REVERSED_RANGE_CONSTRAINT.matcher(compact);
			if (reversedRange.matches()) {
				String name = cleanName(reversedRange.group(3));
				SymbolConstraint constraint = constraints.computeIfAbsent(name, SymbolConstraint::new);
				constraint.applyUpper(Long.parseLong(reversedRange.group(1)), reversedRange.group(2).equals(">="), raw);
				constraint.applyLower(Long.parseLong(reversedRange.group(5)), reversedRange.group(4).equals(">="), raw);
				continue;
			}
			Matcher simple = SIMPLE_CONSTRAINT.matcher(compact);
			if (!simple.matches()) {
				throw new IllegalArgumentException("Invalid constraint '" + raw + "'. Use forms like final<90, A>=10, 2<B<6, or B in [2,3,5].");
			}
			applySimpleConstraint(simple.group(1), simple.group(2), simple.group(3), raw, constraints);
		}
		return constraints;
	}

	private static boolean parseInConstraint(String raw, Map<String, SymbolConstraint> constraints) {
		Matcher matcher = IN_CONSTRAINT.matcher(raw);
		if (!matcher.matches()) {
			return false;
		}
		String name = cleanName(matcher.group(1));
		String values = matcher.group(2).replaceAll("\\s+", "");
		values = values.replace('|', ',');
		List<Integer> allowed = new ArrayList<>();
		for (String value : values.split(",")) {
			if (!value.isBlank()) {
				allowed.add(Integer.parseInt(value));
			}
		}
		if (allowed.isEmpty()) {
			throw new IllegalArgumentException("Constraint '" + raw + "' must include at least one allowed value.");
		}
		constraints.computeIfAbsent(name, SymbolConstraint::new).applyAllowed(allowed, raw);
		return true;
	}

	private static void addTerm(List<String> items, String rawTerm) {
		String term = rawTerm.trim();
		if (term.isBlank()) {
			return;
		}
		String name;
		int count;
		int marker = term.indexOf('*');
		if (marker < 0) {
			name = cleanName(term);
			count = 1;
		} else {
			String left = term.substring(0, marker).trim();
			String right = term.substring(marker + 1).trim();
			if (isInteger(left) && !isInteger(right)) {
				count = Integer.parseInt(left);
				name = cleanName(right);
			} else if (!isInteger(left) && isInteger(right)) {
				name = cleanName(left);
				count = Integer.parseInt(right);
			} else {
				throw new IllegalArgumentException("Invalid sequence term '" + rawTerm + "'. Use Item, Item*count, or count*Item.");
			}
		}
		if (count <= 0 || count > 4096) {
			throw new IllegalArgumentException("Sequence term '" + rawTerm + "' has invalid count " + count + ". Use 1..4096.");
		}
		for (int i = 0; i < count; i++) {
			items.add(name);
		}
	}

	private static void applySimpleConstraint(String left, String operator, String right, String raw, Map<String, SymbolConstraint> constraints) {
		boolean leftNumber = isInteger(left);
		boolean rightNumber = isInteger(right);
		if (leftNumber == rightNumber) {
			throw new IllegalArgumentException("Constraint '" + raw + "' must compare one named sequence/component with one integer.");
		}
		if (!leftNumber) {
			String name = cleanName(left);
			long value = Long.parseLong(right);
			applyNamedComparison(constraints.computeIfAbsent(name, SymbolConstraint::new), operator, value, raw);
		} else {
			String name = cleanName(right);
			long value = Long.parseLong(left);
			applyNamedComparison(constraints.computeIfAbsent(name, SymbolConstraint::new), flipOperator(operator), value, raw);
		}
	}

	private static void applyNamedComparison(SymbolConstraint constraint, String operator, long value, String raw) {
		switch (operator) {
			case "<" -> constraint.applyUpper(value, false, raw);
			case "<=" -> constraint.applyUpper(value, true, raw);
			case ">" -> constraint.applyLower(value, false, raw);
			case ">=" -> constraint.applyLower(value, true, raw);
			case "=", "==" -> constraint.applyAllowed(List.of(Math.toIntExact(value)), raw);
			default -> throw new IllegalArgumentException("Unsupported constraint operator: " + operator);
		}
	}

	private static String flipOperator(String operator) {
		return switch (operator) {
			case "<" -> ">";
			case "<=" -> ">=";
			case ">" -> "<";
			case ">=" -> "<=";
			default -> operator;
		};
	}

	private static LinearExpression expand(String name, Map<String, List<String>> definitions, Set<String> visiting, Map<String, LinearExpression> cache) {
		LinearExpression cached = cache.get(name);
		if (cached != null) {
			return cached;
		}
		List<String> items = definitions.get(name);
		if (items == null) {
			LinearExpression atom = LinearExpression.atom(name);
			cache.put(name, atom);
			return atom;
		}
		if (!visiting.add(name)) {
			throw new IllegalArgumentException("Sequence definitions contain a cycle at " + name + ".");
		}
		LinearExpression result = new LinearExpression();
		for (String item : items) {
			result.add(expand(item, definitions, visiting, cache));
		}
		visiting.remove(name);
		cache.put(name, result.copy());
		return result;
	}

	private static Map<String, Domain> inferDomains(Set<String> atoms, Map<String, LinearExpression> expressions, Map<String, SymbolConstraint> constraints, int maxUnboundedValue, List<String> warnings) {
		Map<String, Domain> domains = new LinkedHashMap<>();
		for (String atom : atoms) {
			domains.put(atom, new Domain(1, maxUnboundedValue));
		}

		for (SymbolConstraint constraint : constraints.values()) {
			Domain domain = domains.get(constraint.name());
			if (domain == null) {
				continue;
			}
			if (constraint.hasLower()) {
				domain.raiseMin(constraint.effectiveLower());
			}
			if (constraint.hasUpper()) {
				domain.lowerMax(constraint.effectiveUpper());
			}
			if (!constraint.allowedValues().isEmpty()) {
				domain.restrictTo(constraint.allowedValues());
			}
		}

		for (SymbolConstraint constraint : constraints.values()) {
			LinearExpression expression = expressions.get(constraint.name());
			if (expression == null) {
				expression = LinearExpression.atom(constraint.name());
			}
			if (constraint.hasUpper()) {
				long upper = constraint.effectiveUpper();
				for (Map.Entry<String, Long> entry : expression.coefficients().entrySet()) {
					Domain domain = domains.get(entry.getKey());
					if (domain == null) {
						continue;
					}
					long otherMinimum = expression.minimum(domains) - entry.getValue() * domain.min();
					long max = Math.floorDiv(upper - otherMinimum, entry.getValue());
					domain.lowerMax(max);
				}
			}
			if (constraint.hasLower() && expression.coefficients().size() == 1) {
				Map.Entry<String, Long> only = expression.coefficients().entrySet().iterator().next();
				Domain domain = domains.get(only.getKey());
				if (domain != null) {
					long numerator = constraint.effectiveLower();
					long min = Math.floorDiv(numerator + only.getValue() - 1L, only.getValue());
					domain.raiseMin(min);
				}
			}
		}

		for (Map.Entry<String, Domain> entry : domains.entrySet()) {
			entry.getValue().finalizeValues();
			if (entry.getValue().empty()) {
				warnings.add("Atomic component " + entry.getKey() + " has an empty integer domain after applying constraints.");
			} else if (entry.getValue().usedDefaultMax()) {
				warnings.add("Atomic component " + entry.getKey() + " had no finite upper bound, so MineAgent searched 1.." + maxUnboundedValue + ".");
			}
		}
		return domains;
	}

	private static ConstraintObjective chooseObjective(String root, Map<String, SymbolConstraint> constraints) {
		SymbolConstraint rootConstraint = constraints.get(root);
		if (rootConstraint != null) {
			return new ConstraintObjective(root, rootConstraint.target());
		}
		SymbolConstraint finalConstraint = constraints.get("final");
		if (finalConstraint != null) {
			return new ConstraintObjective("final", finalConstraint.target());
		}
		if (!constraints.isEmpty()) {
			SymbolConstraint first = constraints.values().iterator().next();
			return new ConstraintObjective(first.name(), first.target());
		}
		return new ConstraintObjective(root, 0.0D);
	}

	private static List<String> searchOrder(Set<String> atoms, Map<String, Domain> domains, LinearExpression objectiveExpression) {
		List<String> order = new ArrayList<>(atoms);
		order.sort(Comparator
				.<String>comparingLong(name -> -objectiveExpression.coefficient(name))
				.thenComparingInt(name -> domains.get(name).values().size())
				.thenComparing(name -> name));
		return order;
	}

	private static void dfs(List<String> variables, int index, Map<String, Integer> assignment, SearchState state) {
		if (state.limitReached()) {
			return;
		}
		if (!state.canStillSatisfy(assignment)) {
			return;
		}
		if (index >= variables.size()) {
			state.visitLeaf();
			if (state.satisfies(assignment)) {
				state.consider(assignment);
			}
			return;
		}
		String variable = variables.get(index);
		for (int value : state.orderedValues(variable, assignment)) {
			assignment.put(variable, value);
			dfs(variables, index + 1, assignment, state);
			assignment.remove(variable);
			if (state.limitReached()) {
				return;
			}
		}
	}

	private static JsonObject definitionsJson(Map<String, List<String>> definitions) {
		JsonObject object = new JsonObject();
		for (Map.Entry<String, List<String>> entry : definitions.entrySet()) {
			object.add(entry.getKey(), strings(entry.getValue()));
		}
		return object;
	}

	private static JsonObject domainsJson(Map<String, Domain> domains) {
		JsonObject object = new JsonObject();
		for (Map.Entry<String, Domain> entry : domains.entrySet()) {
			JsonObject domain = new JsonObject();
			domain.addProperty("min", entry.getValue().min());
			domain.addProperty("max", entry.getValue().max());
			domain.addProperty("candidate_count", entry.getValue().values().size());
			domain.addProperty("used_default_max", entry.getValue().usedDefaultMax());
			object.add(entry.getKey(), domain);
		}
		return object;
	}

	private static JsonArray constraintsJson(Map<String, SymbolConstraint> constraints) {
		JsonArray array = new JsonArray();
		for (SymbolConstraint constraint : constraints.values()) {
			JsonObject object = new JsonObject();
			object.addProperty("name", constraint.name());
			if (constraint.hasLower()) {
				object.addProperty("lower", constraint.lower());
				object.addProperty("lower_inclusive", constraint.lowerInclusive());
			}
			if (constraint.hasUpper()) {
				object.addProperty("upper", constraint.upper());
				object.addProperty("upper_inclusive", constraint.upperInclusive());
			}
			if (!constraint.allowedValues().isEmpty()) {
				JsonArray allowed = new JsonArray();
				for (int value : constraint.allowedValues()) {
					allowed.add(value);
				}
				object.add("allowed_values", allowed);
			}
			object.addProperty("target", constraint.target());
			object.add("raw", strings(constraint.raw()));
			array.add(object);
		}
		return array;
	}

	private static JsonObject objectiveJson(ConstraintObjective objective) {
		JsonObject object = new JsonObject();
		object.addProperty("name", objective.name());
		object.addProperty("target", objective.target());
		return object;
	}

	private static JsonArray resultsJson(List<Result> results, Map<String, LinearExpression> expressions, List<String> variables, ConstraintObjective objective) {
		JsonArray array = new JsonArray();
		int rank = 1;
		for (Result result : results) {
			JsonObject object = new JsonObject();
			object.addProperty("rank", rank++);
			object.addProperty("objective_value", result.value(objective.name()));
			object.addProperty("objective_distance", result.objectiveDistance());
			object.addProperty("total_constraint_distance", result.totalDistance());
			JsonObject atoms = new JsonObject();
			for (String variable : variables) {
				atoms.addProperty(variable, result.assignment().get(variable));
			}
			object.add("atoms", atoms);
			JsonObject values = new JsonObject();
			for (String name : expressions.keySet()) {
				values.addProperty(name, result.value(name));
			}
			object.add("values", values);
			array.add(object);
		}
		return array;
	}

	private static JsonArray strings(List<String> values) {
		JsonArray array = new JsonArray();
		for (String value : values) {
			array.add(value);
		}
		return array;
	}

	private static boolean isInteger(String value) {
		if (value.isBlank()) {
			return false;
		}
		int start = value.charAt(0) == '-' ? 1 : 0;
		if (start == value.length()) {
			return false;
		}
		for (int i = start; i < value.length(); i++) {
			if (!Character.isDigit(value.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static String cleanName(String raw) {
		String name = raw.trim();
		if (!NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("Invalid sequence/component name: " + raw + ". Use letters, digits, and underscore, starting with a letter.");
		}
		return name;
	}

	private static final class LinearExpression {
		private final Map<String, Long> coefficients = new LinkedHashMap<>();

		private static LinearExpression atom(String name) {
			LinearExpression expression = new LinearExpression();
			expression.coefficients.put(name, 1L);
			return expression;
		}

		private Map<String, Long> coefficients() {
			return coefficients;
		}

		private void add(LinearExpression other) {
			for (Map.Entry<String, Long> entry : other.coefficients.entrySet()) {
				coefficients.merge(entry.getKey(), entry.getValue(), Long::sum);
			}
		}

		private LinearExpression copy() {
			LinearExpression expression = new LinearExpression();
			expression.coefficients.putAll(coefficients);
			return expression;
		}

		private long coefficient(String name) {
			return coefficients.getOrDefault(name, 0L);
		}

		private long value(Map<String, Integer> assignment) {
			long total = 0L;
			for (Map.Entry<String, Long> entry : coefficients.entrySet()) {
				Integer value = assignment.get(entry.getKey());
				if (value != null) {
					total += entry.getValue() * value;
				}
			}
			return total;
		}

		private long minimum(Map<String, Domain> domains) {
			long total = 0L;
			for (Map.Entry<String, Long> entry : coefficients.entrySet()) {
				total += entry.getValue() * domains.get(entry.getKey()).min();
			}
			return total;
		}

		private long partialMinimum(Map<String, Integer> assignment, Map<String, Domain> domains) {
			long total = 0L;
			for (Map.Entry<String, Long> entry : coefficients.entrySet()) {
				Integer value = assignment.get(entry.getKey());
				total += entry.getValue() * (value == null ? domains.get(entry.getKey()).min() : value);
			}
			return total;
		}

		private long partialMaximum(Map<String, Integer> assignment, Map<String, Domain> domains) {
			long total = 0L;
			for (Map.Entry<String, Long> entry : coefficients.entrySet()) {
				Integer value = assignment.get(entry.getKey());
				total += entry.getValue() * (value == null ? domains.get(entry.getKey()).max() : value);
			}
			return total;
		}
	}

	private static final class SymbolConstraint {
		private final String name;
		private final List<String> raw = new ArrayList<>();
		private final Set<Integer> allowedValues = new LinkedHashSet<>();
		private Long lower;
		private boolean lowerInclusive = true;
		private Long upper;
		private boolean upperInclusive = true;

		private SymbolConstraint(String name) {
			this.name = name;
		}

		private String name() {
			return name;
		}

		private List<String> raw() {
			return raw;
		}

		private void applyLower(long value, boolean inclusive, String rawConstraint) {
			raw.add(rawConstraint);
			if (lower == null || value > lower || value == lower && !inclusive && lowerInclusive) {
				lower = value;
				lowerInclusive = inclusive;
			}
		}

		private void applyUpper(long value, boolean inclusive, String rawConstraint) {
			raw.add(rawConstraint);
			if (upper == null || value < upper || value == upper && !inclusive && upperInclusive) {
				upper = value;
				upperInclusive = inclusive;
			}
		}

		private void applyAllowed(List<Integer> values, String rawConstraint) {
			raw.add(rawConstraint);
			if (allowedValues.isEmpty()) {
				allowedValues.addAll(values);
			} else {
				allowedValues.retainAll(values);
			}
		}

		private boolean hasLower() {
			return lower != null;
		}

		private boolean hasUpper() {
			return upper != null;
		}

		private long lower() {
			return lower == null ? Long.MIN_VALUE : lower;
		}

		private boolean lowerInclusive() {
			return lowerInclusive;
		}

		private long upper() {
			return upper == null ? Long.MAX_VALUE : upper;
		}

		private boolean upperInclusive() {
			return upperInclusive;
		}

		private Set<Integer> allowedValues() {
			return allowedValues;
		}

		private long effectiveLower() {
			if (lower == null) {
				return Long.MIN_VALUE;
			}
			return lowerInclusive ? lower : lower + 1L;
		}

		private long effectiveUpper() {
			if (upper == null) {
				return Long.MAX_VALUE;
			}
			return upperInclusive ? upper : upper - 1L;
		}

		private boolean accepts(long value) {
			if (hasLower() && value < effectiveLower()) {
				return false;
			}
			if (hasUpper() && value > effectiveUpper()) {
				return false;
			}
			if (!allowedValues.isEmpty() && !allowedValues.contains(Math.toIntExact(value))) {
				return false;
			}
			return true;
		}

		private double target() {
			if (hasLower() && hasUpper()) {
				return (lower + upper) / 2.0D;
			}
			if (hasUpper()) {
				return upper;
			}
			if (hasLower()) {
				return lower;
			}
			if (!allowedValues.isEmpty()) {
				long total = 0L;
				for (int value : allowedValues) {
					total += value;
				}
				return total / (double) allowedValues.size();
			}
			return 0.0D;
		}
	}

	private static final class Domain {
		private long min;
		private long max;
		private final int initialMax;
		private final Set<Integer> allowedValues = new LinkedHashSet<>();
		private List<Integer> values = List.of();

		private Domain(int min, int max) {
			this.min = min;
			this.max = max;
			this.initialMax = max;
		}

		private long min() {
			return min;
		}

		private long max() {
			return max;
		}

		private List<Integer> values() {
			return values;
		}

		private boolean empty() {
			return values.isEmpty();
		}

		private boolean usedDefaultMax() {
			return max >= initialMax && allowedValues.isEmpty();
		}

		private void raiseMin(long value) {
			min = Math.max(min, value);
		}

		private void lowerMax(long value) {
			max = Math.min(max, value);
		}

		private void restrictTo(Set<Integer> values) {
			if (allowedValues.isEmpty()) {
				allowedValues.addAll(values);
			} else {
				allowedValues.retainAll(values);
			}
		}

		private void finalizeValues() {
			List<Integer> finalized = new ArrayList<>();
			if (!allowedValues.isEmpty()) {
				for (int value : allowedValues) {
					if (value >= min && value <= max) {
						finalized.add(value);
					}
				}
				finalized.sort(Integer::compareTo);
			} else if (min <= max) {
				for (long value = min; value <= max; value++) {
					finalized.add(Math.toIntExact(value));
				}
			}
			values = List.copyOf(finalized);
		}
	}

	private record ConstraintObjective(String name, double target) {
	}

	private static final class SearchState {
		private final int maxResults;
		private final ConstraintObjective objective;
		private final Map<String, LinearExpression> expressions;
		private final Map<String, SymbolConstraint> constraints;
		private final Map<String, Domain> domains;
		private final Comparator<Result> bestFirst;
		private final PriorityQueue<Result> best;
		private int visitedLeaves;
		private boolean limitReached;

		private SearchState(int maxResults, ConstraintObjective objective, Map<String, LinearExpression> expressions, Map<String, SymbolConstraint> constraints, Map<String, Domain> domains) {
			this.maxResults = maxResults;
			this.objective = objective;
			this.expressions = expressions;
			this.constraints = constraints;
			this.domains = domains;
			this.bestFirst = Comparator
					.comparingDouble(Result::objectiveDistance)
					.thenComparingDouble(Result::totalDistance)
					.thenComparingLong(result -> result.value(objective.name()))
					.thenComparing(result -> result.assignment().toString());
			this.best = new PriorityQueue<>(bestFirst.reversed());
		}

		private boolean limitReached() {
			return limitReached;
		}

		private int visitedLeaves() {
			return visitedLeaves;
		}

		private void visitLeaf() {
			visitedLeaves++;
			if (visitedLeaves >= MAX_GREEDY_LEAVES) {
				limitReached = true;
			}
		}

		private boolean canStillSatisfy(Map<String, Integer> assignment) {
			for (SymbolConstraint constraint : constraints.values()) {
				LinearExpression expression = expressions.getOrDefault(constraint.name(), LinearExpression.atom(constraint.name()));
				long min = expression.partialMinimum(assignment, domains);
				long max = expression.partialMaximum(assignment, domains);
				if (constraint.hasLower() && max < constraint.effectiveLower()) {
					return false;
				}
				if (constraint.hasUpper() && min > constraint.effectiveUpper()) {
					return false;
				}
				if (!constraint.allowedValues().isEmpty()) {
					boolean possible = false;
					for (int value : constraint.allowedValues()) {
						if (value >= min && value <= max) {
							possible = true;
							break;
						}
					}
					if (!possible) {
						return false;
					}
				}
			}
			return true;
		}

		private boolean satisfies(Map<String, Integer> assignment) {
			for (SymbolConstraint constraint : constraints.values()) {
				LinearExpression expression = expressions.getOrDefault(constraint.name(), LinearExpression.atom(constraint.name()));
				if (!constraint.accepts(expression.value(assignment))) {
					return false;
				}
			}
			return true;
		}

		private List<Integer> orderedValues(String variable, Map<String, Integer> assignment) {
			List<Integer> values = new ArrayList<>(domains.get(variable).values());
			LinearExpression objectiveExpression = expressions.getOrDefault(objective.name(), LinearExpression.atom(objective.name()));
			long coefficient = objectiveExpression.coefficient(variable);
			if (coefficient == 0L) {
				return values;
			}
			long fixed = objectiveExpression.value(assignment);
			for (Map.Entry<String, Long> entry : objectiveExpression.coefficients().entrySet()) {
				if (!entry.getKey().equals(variable) && !assignment.containsKey(entry.getKey())) {
					fixed += entry.getValue() * domains.get(entry.getKey()).min();
				}
			}
			double targetValue = (objective.target() - fixed) / coefficient;
			values.sort(Comparator
					.comparingDouble((Integer value) -> Math.abs(value - targetValue))
					.thenComparingInt(Integer::intValue));
			return values;
		}

		private void consider(Map<String, Integer> assignment) {
			Map<String, Integer> frozen = Map.copyOf(assignment);
			Map<String, Long> values = new LinkedHashMap<>();
			for (Map.Entry<String, LinearExpression> entry : expressions.entrySet()) {
				values.put(entry.getKey(), entry.getValue().value(frozen));
			}
			double objectiveDistance = Math.abs(values.getOrDefault(objective.name(), 0L) - objective.target());
			double totalDistance = 0.0D;
			for (SymbolConstraint constraint : constraints.values()) {
				long value = values.getOrDefault(constraint.name(), expressions.getOrDefault(constraint.name(), LinearExpression.atom(constraint.name())).value(frozen));
				totalDistance += Math.abs(value - constraint.target());
			}
			Result result = new Result(frozen, values, objectiveDistance, totalDistance);
			if (best.size() < maxResults) {
				best.add(result);
			} else if (bestFirst.compare(result, best.peek()) < 0) {
				best.poll();
				best.add(result);
			}
		}

		private List<Result> sortedResults() {
			List<Result> results = new ArrayList<>(best);
			results.sort(bestFirst);
			return results;
		}
	}

	private record Result(Map<String, Integer> assignment, Map<String, Long> values, double objectiveDistance, double totalDistance) {
		private long value(String name) {
			return values.getOrDefault(name, 0L);
		}
	}
}
