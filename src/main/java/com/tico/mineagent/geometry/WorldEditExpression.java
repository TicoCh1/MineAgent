package com.tico.mineagent.geometry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.BlockPos;

/**
 * MineAgent's bounded WorldEdit-expression subset for MCP //gen.
 *
 * <p>This intentionally supports the parts that are useful for shape generation
 * without importing WorldEdit's ANTLR/runtime stack: statements, blocks, if/else,
 * return, assignment, ++/--, mutable x/y/z/type/data, random/noise, block query
 * functions, and type/data output rewrite. Loops and megabuf/gmegabuf remain
 * unsupported until MineAgent has a stronger execution-budget story.</p>
 */
public final class WorldEditExpression {
	private static final double TRUE = 1.0D;
	private static final double FALSE = 0.0D;
	private static final double NEAR_EPSILON = 1.0E-6D;

	private final String source;
	private final Node root;
	private final Effects effects;
	private final Map<String, Double> variables = new HashMap<>();
	private final NoiseCache noiseCache = new NoiseCache();

	private WorldEditExpression(String source, Node root, Effects effects) {
		this.source = source;
		this.root = root;
		this.effects = effects;
	}

	public static WorldEditExpression compile(String source) {
		String trimmed = source == null ? "" : source.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("mineagent_gen expression must not be blank.");
		}
		Parser parser = new Parser(trimmed);
		return new WorldEditExpression(trimmed, parser.parse(), parser.effects());
	}

	public String source() {
		return source;
	}

	public Effects effects() {
		return effects;
	}

	public EvaluationResult evaluate(BlockPos pos, Bounds bounds, BlockTypeData defaultTypeData, BlockSampler blockSampler) {
		Context context = new Context(pos, bounds, defaultTypeData, blockSampler, variables, noiseCache);
		try {
			Double value = root.evaluate(context);
			if (value == null) {
				throw new IllegalArgumentException("mineagent_gen expression must result in a numeric value.");
			}
			return new EvaluationResult(value, context.typeData());
		} catch (ReturnSignal signal) {
			return new EvaluationResult(signal.value(), context.typeData());
		}
	}

	public List<String> enabledOptimizations() {
		List<String> result = new ArrayList<>();
		result.add("precomputed default type/data mapping for the target block");
		if (!effects.usesTypeData() && !effects.writesTypeData()) {
			result.add("skip dynamic type/data output rewrite because expression does not read or write type/data");
		}
		if (effects.usesNoise()) {
			result.add("cache deterministic noise generators by seed/frequency/octave parameters");
		}
		if (effects.pureCoordinateMath()) {
			result.add("classified as pure coordinate math; no world-query, random, mutable-state, or type/data side effects");
		}
		return List.copyOf(result);
	}

	public List<String> disabledOptimizations() {
		List<String> result = new ArrayList<>();
		if (effects.usesRandom()) {
			result.add("disabled deterministic/result caching because expression uses random()");
		}
		if (effects.usesQuery()) {
			result.add("disabled block-query caching because query/queryRel/queryAbs must observe current edit order and preserve type/data assignment side effects");
		}
		if (effects.usesMutableState()) {
			result.add("disabled stateless/parallel evaluation because assignment, increment, rotate/swap, or query output variables can make block order observable");
		}
		if (effects.usesTypeData() || effects.writesTypeData()) {
			result.add("disabled fixed-output-block fast path because expression reads or writes type/data");
		}
		if (effects.usesStatements()) {
			result.add("disabled expression algebra rewrites across statements because statement order can carry side effects");
		}
		return List.copyOf(result);
	}

	private static boolean truthy(double value) {
		return value > 0.0D;
	}

	private static double bool(boolean value) {
		return value ? TRUE : FALSE;
	}

	private static double requireValue(Double value, String label) {
		if (value == null) {
			throw new IllegalArgumentException("Expected " + label + " to produce a numeric value.");
		}
		return value;
	}

	private static boolean near(double a, double b) {
		double tolerance = NEAR_EPSILON * Math.max(1.0D, Math.max(Math.abs(a), Math.abs(b)));
		return Math.abs(a - b) <= tolerance;
	}

	private static boolean isIdentifierStart(char ch) {
		return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || ch == '_';
	}

	private static boolean isIdentifierPart(char ch) {
		return isIdentifierStart(ch) || (ch >= '0' && ch <= '9');
	}

	public record Effects(
			boolean usesStatements,
			boolean usesMutableState,
			boolean usesRandom,
			boolean usesNoise,
			boolean usesQuery,
			boolean usesTypeData,
			boolean writesTypeData) {
		public boolean pureCoordinateMath() {
			return !usesStatements
					&& !usesMutableState
					&& !usesRandom
					&& !usesNoise
					&& !usesQuery
					&& !usesTypeData
					&& !writesTypeData;
		}

		public boolean dynamicOutputBlock() {
			return writesTypeData;
		}
	}

	private static final class EffectsBuilder {
		private boolean usesStatements;
		private boolean usesMutableState;
		private boolean usesRandom;
		private boolean usesNoise;
		private boolean usesQuery;
		private boolean usesTypeData;
		private boolean writesTypeData;

		private Effects build() {
			return new Effects(usesStatements, usesMutableState, usesRandom, usesNoise, usesQuery, usesTypeData, writesTypeData);
		}

		private void statement() {
			usesStatements = true;
		}

		private void variableRead(String name) {
			if ("type".equals(name) || "data".equals(name)) {
				usesTypeData = true;
			}
		}

		private void variableWrite(String name) {
			usesMutableState = true;
			if ("type".equals(name) || "data".equals(name)) {
				writesTypeData = true;
				usesTypeData = true;
			}
		}

		private void random() {
			usesRandom = true;
		}

		private void noise() {
			usesNoise = true;
		}

		private void query() {
			usesQuery = true;
			usesMutableState = true;
		}
	}

	public record Bounds(BlockPos min, BlockPos max) {
		public static Bounds of(BlockPos first, BlockPos second) {
			return new Bounds(
					new BlockPos(
							Math.min(first.getX(), second.getX()),
							Math.min(first.getY(), second.getY()),
							Math.min(first.getZ(), second.getZ())),
					new BlockPos(
							Math.max(first.getX(), second.getX()),
							Math.max(first.getY(), second.getY()),
							Math.max(first.getZ(), second.getZ())));
		}

		private double normalizedX(BlockPos pos) {
			return normalized(pos.getX(), min.getX(), max.getX());
		}

		private double normalizedY(BlockPos pos) {
			return normalized(pos.getY(), min.getY(), max.getY());
		}

		private double normalizedZ(BlockPos pos) {
			return normalized(pos.getZ(), min.getZ(), max.getZ());
		}

		private double localX(BlockPos pos) {
			return (long) pos.getX() - min.getX();
		}

		private double localY(BlockPos pos) {
			return (long) pos.getY() - min.getY();
		}

		private double localZ(BlockPos pos) {
			return (long) pos.getZ() - min.getZ();
		}

		private double sizeX() {
			return (long) max.getX() - min.getX() + 1L;
		}

		private double sizeY() {
			return (long) max.getY() - min.getY() + 1L;
		}

		private double sizeZ() {
			return (long) max.getZ() - min.getZ() + 1L;
		}

		private double centerX() {
			return midpoint(min.getX(), max.getX());
		}

		private double centerY() {
			return midpoint(min.getY(), max.getY());
		}

		private double centerZ() {
			return midpoint(min.getZ(), max.getZ());
		}

		private double scaleX() {
			return scale(min.getX(), max.getX());
		}

		private double scaleY() {
			return scale(min.getY(), max.getY());
		}

		private double scaleZ() {
			return scale(min.getZ(), max.getZ());
		}

		private BlockPos toWorld(double x, double y, double z) {
			return new BlockPos(
					roundToBlock(centerX() + x * scaleX()),
					roundToBlock(centerY() + y * scaleY()),
					roundToBlock(centerZ() + z * scaleZ()));
		}

		private static double normalized(int coordinate, int min, int max) {
			double center = midpoint(min, max);
			return (coordinate - center) / scale(min, max);
		}

		private static double midpoint(int min, int max) {
			return min + (((long) max - min) / 2.0D);
		}

		private static double scale(int min, int max) {
			double center = midpoint(min, max);
			double scale = max - center;
			return scale == 0.0D ? 1.0D : scale;
		}
	}

	public record BlockTypeData(int type, int data) {
	}

	public record EvaluationResult(double value, BlockTypeData typeData) {
	}

	@FunctionalInterface
	public interface BlockSampler {
		BlockTypeData sample(BlockPos pos);
	}

	private static final class Context {
		private final BlockPos pos;
		private final Bounds bounds;
		private final BlockTypeData defaultTypeData;
		private final BlockSampler blockSampler;
		private final Map<String, Double> variables;
		private final NoiseCache noiseCache;

		private Context(BlockPos pos, Bounds bounds, BlockTypeData defaultTypeData, BlockSampler blockSampler, Map<String, Double> variables, NoiseCache noiseCache) {
			this.pos = pos;
			this.bounds = bounds;
			this.defaultTypeData = defaultTypeData;
			this.blockSampler = blockSampler;
			this.variables = variables;
			this.noiseCache = noiseCache;
			setProvided("x", bounds.normalizedX(pos));
			setProvided("y", bounds.normalizedY(pos));
			setProvided("z", bounds.normalizedZ(pos));
			setProvided("type", defaultTypeData.type());
			setProvided("data", defaultTypeData.data());
		}

		private void setProvided(String name, double value) {
			variables.put(name, value);
		}

		private double variable(String name) {
			return switch (name) {
				case "world_x", "wx" -> pos.getX();
				case "world_y", "wy" -> pos.getY();
				case "world_z", "wz" -> pos.getZ();
				case "lx" -> bounds.localX(pos);
				case "ly" -> bounds.localY(pos);
				case "lz" -> bounds.localZ(pos);
				case "sx" -> bounds.sizeX();
				case "sy" -> bounds.sizeY();
				case "sz" -> bounds.sizeZ();
				case "pi" -> Math.PI;
				case "e" -> Math.E;
				case "true" -> TRUE;
				case "false" -> FALSE;
				default -> {
					Double value = variables.get(name);
					if (value == null) {
						throw new IllegalArgumentException("Variable '" + name + "' is not initialized.");
					}
					yield value;
				}
			};
		}

		private void assign(String name, double value) {
			if (isReadOnlyVariable(name)) {
				throw new IllegalArgumentException("Cannot assign to read-only expression variable '" + name + "'.");
			}
			variables.put(name, value);
		}

		private BlockTypeData typeData() {
			return new BlockTypeData((int) Math.round(variable("type")), (int) Math.round(variable("data")));
		}

		private BlockTypeData query(double x, double y, double z) {
			return blockSampler.sample(bounds.toWorld(x, y, z));
		}

		private BlockTypeData queryAbs(double x, double y, double z) {
			return blockSampler.sample(new BlockPos(floorToBlock(x), floorToBlock(y), floorToBlock(z)));
		}

		private BlockTypeData queryRel(double x, double y, double z) {
			return blockSampler.sample(new BlockPos(
					floorToBlock(pos.getX() + x),
					floorToBlock(pos.getY() + y),
					floorToBlock(pos.getZ() + z)));
		}

		private static boolean isReadOnlyVariable(String name) {
			return switch (name) {
				case "world_x", "world_y", "world_z", "wx", "wy", "wz", "lx", "ly", "lz", "sx", "sy", "sz", "pi", "e", "true", "false" -> true;
				default -> false;
			};
		}
	}

	@FunctionalInterface
	private interface Node {
		Double evaluate(Context context);
	}

	private record LiteralNode(double value) implements Node {
		@Override
		public Double evaluate(Context context) {
			return value;
		}
	}

	private record VariableNode(String name) implements Node {
		@Override
		public Double evaluate(Context context) {
			return context.variable(name);
		}
	}

	private record BlockNode(List<Node> statements) implements Node {
		@Override
		public Double evaluate(Context context) {
			Double result = null;
			for (Node statement : statements) {
				result = statement.evaluate(context);
			}
			return result;
		}
	}

	private record IfNode(Node condition, Node trueBranch, Node falseBranch) implements Node {
		@Override
		public Double evaluate(Context context) {
			if (truthy(requireValue(condition.evaluate(context), "if condition"))) {
				return trueBranch.evaluate(context);
			}
			return falseBranch == null ? null : falseBranch.evaluate(context);
		}
	}

	private record ReturnNode(Node value) implements Node {
		@Override
		public Double evaluate(Context context) {
			throw new ReturnSignal(requireValue(value.evaluate(context), "return"));
		}
	}

	private record AssignmentNode(String name, String operator, Node value) implements Node {
		@Override
		public Double evaluate(Context context) {
			double right = requireValue(value.evaluate(context), "assignment right side");
			double result;
			if ("=".equals(operator)) {
				result = right;
			} else {
				double left = context.variable(name);
				result = switch (operator) {
					case "+=" -> left + right;
					case "-=" -> left - right;
					case "*=" -> left * right;
					case "/=" -> left / right;
					case "%=" -> left % right;
					case "^=" -> Math.pow(left, right);
					default -> throw new IllegalStateException("Unsupported assignment operator: " + operator);
				};
			}
			context.assign(name, result);
			return result;
		}
	}

	private record CrementNode(String name, int delta, boolean prefix) implements Node {
		@Override
		public Double evaluate(Context context) {
			double oldValue = context.variable(name);
			double newValue = oldValue + delta;
			context.assign(name, newValue);
			return prefix ? newValue : oldValue;
		}
	}

	private record UnaryNode(String operator, Node operand) implements Node {
		@Override
		public Double evaluate(Context context) {
			double value = requireValue(operand.evaluate(context), "unary operand");
			return switch (operator) {
				case "+" -> value;
				case "-" -> -value;
				case "!" -> truthy(value) ? FALSE : TRUE;
				case "~" -> (double) ~(long) value;
				default -> throw new IllegalStateException("Unsupported unary operator: " + operator);
			};
		}
	}

	private record FactorialNode(Node operand) implements Node {
		@Override
		public Double evaluate(Context context) {
			double value = requireValue(operand.evaluate(context), "factorial operand");
			int n = (int) value;
			if (n < 0) {
				return 0.0D;
			}
			if (n > 170) {
				return Double.POSITIVE_INFINITY;
			}
			double result = 1.0D;
			for (int i = 2; i <= n; i++) {
				result *= i;
			}
			return result;
		}
	}

	private record BinaryNode(String operator, Node left, Node right) implements Node {
		@Override
		public Double evaluate(Context context) {
			if ("&&".equals(operator)) {
				double a = requireValue(left.evaluate(context), "left operand");
				return truthy(a) ? requireValue(right.evaluate(context), "right operand") : FALSE;
			}
			if ("||".equals(operator)) {
				double a = requireValue(left.evaluate(context), "left operand");
				return truthy(a) ? a : requireValue(right.evaluate(context), "right operand");
			}

			double a = requireValue(left.evaluate(context), "left operand");
			double b = requireValue(right.evaluate(context), "right operand");
			return switch (operator) {
				case "+" -> a + b;
				case "-" -> a - b;
				case "*" -> a * b;
				case "/" -> a / b;
				case "%" -> a % b;
				case "^" -> Math.pow(a, b);
				case "<<" -> (double) ((long) a << (long) b);
				case ">>" -> (double) ((long) a >> (long) b);
				case "<" -> bool(a < b);
				case "<=" -> bool(a <= b);
				case ">" -> bool(a > b);
				case ">=" -> bool(a >= b);
				case "==" -> bool(a == b);
				case "!=" -> bool(a != b);
				case "~=" -> bool(near(a, b));
				default -> throw new IllegalStateException("Unsupported binary operator: " + operator);
			};
		}
	}

	private record TernaryNode(Node condition, Node trueBranch, Node falseBranch) implements Node {
		@Override
		public Double evaluate(Context context) {
			return truthy(requireValue(condition.evaluate(context), "ternary condition"))
					? trueBranch.evaluate(context)
					: falseBranch.evaluate(context);
		}
	}

	private record FunctionNode(String name, List<Node> args) implements Node {
		@Override
		public Double evaluate(Context context) {
			return switch (name) {
				case "query" -> query(context, QueryMode.LOCAL);
				case "queryabs" -> query(context, QueryMode.ABSOLUTE);
				case "queryrel" -> query(context, QueryMode.RELATIVE);
				case "rotate" -> rotate(context);
				case "swap" -> swap(context);
				default -> callNumeric(context);
			};
		}

		private double callNumeric(Context context) {
			double[] values = values(context);
			return switch (name) {
				case "abs" -> unary(name, values, Math::abs);
				case "acos" -> unary(name, values, Math::acos);
				case "asin" -> unary(name, values, Math::asin);
				case "atan" -> unary(name, values, Math::atan);
				case "cbrt" -> unary(name, values, Math::cbrt);
				case "ceil" -> unary(name, values, Math::ceil);
				case "cos" -> unary(name, values, Math::cos);
				case "cosh" -> unary(name, values, Math::cosh);
				case "exp" -> unary(name, values, Math::exp);
				case "floor" -> unary(name, values, Math::floor);
				case "ln", "log" -> unary(name, values, Math::log);
				case "log10" -> unary(name, values, Math::log10);
				case "rint" -> unary(name, values, Math::rint);
				case "round" -> unary(name, values, value -> (double) Math.round(value));
				case "sin" -> unary(name, values, Math::sin);
				case "sinh" -> unary(name, values, Math::sinh);
				case "sqrt" -> unary(name, values, Math::sqrt);
				case "tan" -> unary(name, values, Math::tan);
				case "tanh" -> unary(name, values, Math::tanh);
				case "atan2" -> binary(name, values, Math::atan2);
				case "hypot" -> binary(name, values, Math::hypot);
				case "pow" -> binary(name, values, Math::pow);
				case "min" -> min(name, values);
				case "max" -> max(name, values);
				case "clamp" -> clamp(name, values);
				case "signum" -> unary(name, values, Math::signum);
				case "if" -> choose(name, values);
				case "random" -> random(name, values);
				case "randint" -> randint(name, values);
				case "perlin" -> perlin(context, name, values);
				case "voronoi" -> voronoi(context, name, values);
				case "ridgedmulti" -> ridgedMulti(context, name, values);
				case "megabuf", "gmegabuf", "closest", "gclosest" -> throw new IllegalArgumentException("mineagent_gen does not support megabuf/gmegabuf/closest/gclosest yet.");
				default -> throw new IllegalArgumentException("Unsupported mineagent_gen expression function '" + name + "'.");
			};
		}

		private double query(Context context, QueryMode mode) {
			requireArity(name, args.size(), 5);
			double x = requireValue(args.get(0).evaluate(context), "query x");
			double y = requireValue(args.get(1).evaluate(context), "query y");
			double z = requireValue(args.get(2).evaluate(context), "query z");
			QueryArgument type = queryArgument(args.get(3), context, "type");
			QueryArgument data = queryArgument(args.get(4), context, "data");
			BlockTypeData block = switch (mode) {
				case LOCAL -> context.query(x, y, z);
				case ABSOLUTE -> context.queryAbs(x, y, z);
				case RELATIVE -> context.queryRel(x, y, z);
			};
			boolean matches = (type.expected() == -1.0D || block.type() == (int) Math.round(type.expected()))
					&& (data.expected() == -1.0D || block.data() == (int) Math.round(data.expected()));
			type.assign(context, block.type());
			data.assign(context, block.data());
			return bool(matches);
		}

		private QueryArgument queryArgument(Node node, Context context, String label) {
			if (node instanceof VariableNode variable) {
				return new QueryArgument(variable.name(), context.variable(variable.name()));
			}
			return new QueryArgument(null, requireValue(node.evaluate(context), "query " + label));
		}

		private double rotate(Context context) {
			requireArity(name, args.size(), 3);
			VariableNode x = variableArg(args.get(0), "rotate first argument");
			VariableNode y = variableArg(args.get(1), "rotate second argument");
			double angle = requireValue(args.get(2).evaluate(context), "rotate angle");
			double cos = Math.cos(angle);
			double sin = Math.sin(angle);
			double oldX = context.variable(x.name());
			double oldY = context.variable(y.name());
			context.assign(x.name(), oldX * cos - oldY * sin);
			context.assign(y.name(), oldX * sin + oldY * cos);
			return 0.0D;
		}

		private double swap(Context context) {
			requireArity(name, args.size(), 2);
			VariableNode a = variableArg(args.get(0), "swap first argument");
			VariableNode b = variableArg(args.get(1), "swap second argument");
			double oldA = context.variable(a.name());
			context.assign(a.name(), context.variable(b.name()));
			context.assign(b.name(), oldA);
			return 0.0D;
		}

		private VariableNode variableArg(Node node, String label) {
			if (node instanceof VariableNode variable) {
				return variable;
			}
			throw new IllegalArgumentException(label + " must be a variable.");
		}

		private double[] values(Context context) {
			double[] values = new double[args.size()];
			for (int i = 0; i < args.size(); i++) {
				values[i] = requireValue(args.get(i).evaluate(context), "function argument");
			}
			return values;
		}
	}

	private record QueryArgument(String variableName, double expected) {
		private void assign(Context context, double value) {
			if (variableName != null) {
				context.assign(variableName, value);
			}
		}
	}

	private enum QueryMode {
		LOCAL,
		ABSOLUTE,
		RELATIVE
	}

	private static final class ReturnSignal extends RuntimeException {
		private final double value;

		private ReturnSignal(double value) {
			super(null, null, false, false);
			this.value = value;
		}

		private double value() {
			return value;
		}
	}

	private static double unary(String name, double[] args, UnaryFunction function) {
		requireArity(name, args.length, 1);
		return function.apply(args[0]);
	}

	private static double binary(String name, double[] args, BinaryFunction function) {
		requireArity(name, args.length, 2);
		return function.apply(args[0], args[1]);
	}

	private static double min(String name, double[] args) {
		requireAtLeast(name, args.length, 1);
		double result = args[0];
		for (int i = 1; i < args.length; i++) {
			result = Math.min(result, args[i]);
		}
		return result;
	}

	private static double max(String name, double[] args) {
		requireAtLeast(name, args.length, 1);
		double result = args[0];
		for (int i = 1; i < args.length; i++) {
			result = Math.max(result, args[i]);
		}
		return result;
	}

	private static double clamp(String name, double[] args) {
		requireArity(name, args.length, 3);
		return Math.max(args[1], Math.min(args[2], args[0]));
	}

	private static double choose(String name, double[] args) {
		requireArity(name, args.length, 3);
		return truthy(args[0]) ? args[1] : args[2];
	}

	private static double random(String name, double[] args) {
		requireArity(name, args.length, 0);
		return ThreadLocalRandom.current().nextDouble();
	}

	private static double randint(String name, double[] args) {
		requireArity(name, args.length, 1);
		return ThreadLocalRandom.current().nextInt(Math.max(1, (int) Math.floor(args[0])));
	}

	private static double perlin(Context context, String name, double[] args) {
		requireArity(name, args.length, 7);
		return context.noiseCache.perlin(args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
	}

	private static double voronoi(Context context, String name, double[] args) {
		requireArity(name, args.length, 5);
		return context.noiseCache.voronoi(args[0], args[1], args[2], args[3], args[4]);
	}

	private static double ridgedMulti(Context context, String name, double[] args) {
		requireArity(name, args.length, 6);
		return context.noiseCache.ridgedMulti(args[0], args[1], args[2], args[3], args[4], args[5]);
	}

	private static void requireArity(String name, int actual, int expected) {
		if (actual != expected) {
			throw new IllegalArgumentException("Function " + name + " expects " + expected + " argument(s), got " + actual + ".");
		}
	}

	private static void requireAtLeast(String name, int actual, int minimum) {
		if (actual < minimum) {
			throw new IllegalArgumentException("Function " + name + " expects at least " + minimum + " argument(s), got " + actual + ".");
		}
	}

	@FunctionalInterface
	private interface UnaryFunction {
		double apply(double value);
	}

	@FunctionalInterface
	private interface BinaryFunction {
		double apply(double left, double right);
	}

	private static final class Parser {
		private final String source;
		private final EffectsBuilder effects = new EffectsBuilder();
		private int position;

		private Parser(String source) {
			this.source = source;
		}

		private Node parse() {
			Node node = parseStatements('\0');
			skipWhitespace();
			if (!isAtEnd()) {
				throw error("Unexpected token '" + source.charAt(position) + "'.");
			}
			return node;
		}

		private Effects effects() {
			return effects.build();
		}

		private Node parseStatements(char terminator) {
			List<Node> statements = new ArrayList<>();
			while (true) {
				skipWhitespace();
				if (isAtEnd()) {
					if (terminator != '\0') {
						throw error("Expected '" + terminator + "'.");
					}
					break;
				}
				if (terminator != '\0' && peek(terminator)) {
					position++;
					break;
				}
				if (match(";")) {
					effects.statement();
					continue;
				}
				statements.add(parseStatement());
				skipWhitespace();
				if (match(";")) {
					effects.statement();
				}
			}
			if (statements.isEmpty()) {
				return new BlockNode(List.of());
			}
			if (statements.size() == 1 && terminator == '\0') {
				return statements.getFirst();
			}
			effects.statement();
			return new BlockNode(List.copyOf(statements));
		}

		private Node parseStatement() {
			skipWhitespace();
			if (match("{")) {
				effects.statement();
				return parseStatements('}');
			}
			if (matchKeyword("if")) {
				effects.statement();
				expect("(");
				Node condition = parseExpression();
				expect(")");
				Node trueBranch = parseStatement();
				Node falseBranch = null;
				if (matchKeyword("else")) {
					falseBranch = parseStatement();
				}
				return new IfNode(condition, trueBranch, falseBranch);
			}
			if (matchKeyword("return")) {
				effects.statement();
				return new ReturnNode(parseExpression());
			}
			if (matchKeyword("while") || matchKeyword("do") || matchKeyword("for")) {
				throw error("mineagent_gen does not support loops yet. Rewrite using bounded expressions or split the operation.");
			}
			if (matchKeyword("break") || matchKeyword("continue") || matchKeyword("switch")) {
				throw error("mineagent_gen does not support break/continue/switch yet.");
			}
			return parseExpression();
		}

		private Node parseExpression() {
			return parseAssignment();
		}

		private Node parseAssignment() {
			skipWhitespace();
			int start = position;
			if (!isAtEnd() && isIdentifierStart(source.charAt(position))) {
				String name = parseIdentifier().toLowerCase(Locale.ROOT);
				skipWhitespace();
				String operator = assignmentOperator();
				if (operator != null) {
					effects.variableWrite(name);
					return new AssignmentNode(name, operator, parseAssignment());
				}
				position = start;
			}
			return parseTernary();
		}

		private String assignmentOperator() {
			for (String operator : List.of("+=", "-=", "*=", "/=", "%=", "^=", "=")) {
				if (source.startsWith(operator, position)) {
					if ("=".equals(operator) && source.startsWith("==", position)) {
						return null;
					}
					position += operator.length();
					return operator;
				}
			}
			return null;
		}

		private Node parseTernary() {
			Node condition = parseOr();
			skipWhitespace();
			if (match("?")) {
				Node trueBranch = parseExpression();
				expect(":");
				Node falseBranch = parseTernary();
				return new TernaryNode(condition, trueBranch, falseBranch);
			}
			return condition;
		}

		private Node parseOr() {
			Node node = parseAnd();
			while (match("||")) {
				node = new BinaryNode("||", node, parseAnd());
			}
			return node;
		}

		private Node parseAnd() {
			Node node = parseEquality();
			while (match("&&")) {
				node = new BinaryNode("&&", node, parseEquality());
			}
			return node;
		}

		private Node parseEquality() {
			Node node = parseComparison();
			while (true) {
				if (match("==")) {
					node = new BinaryNode("==", node, parseComparison());
				} else if (match("!=")) {
					node = new BinaryNode("!=", node, parseComparison());
				} else if (match("~=")) {
					node = new BinaryNode("~=", node, parseComparison());
				} else {
					return node;
				}
			}
		}

		private Node parseComparison() {
			Node node = parseShift();
			while (true) {
				if (match("<=")) {
					node = new BinaryNode("<=", node, parseShift());
				} else if (match(">=")) {
					node = new BinaryNode(">=", node, parseShift());
				} else if (match("<")) {
					node = new BinaryNode("<", node, parseShift());
				} else if (match(">")) {
					node = new BinaryNode(">", node, parseShift());
				} else {
					return node;
				}
			}
		}

		private Node parseShift() {
			Node node = parseAdd();
			while (true) {
				if (match("<<")) {
					node = new BinaryNode("<<", node, parseAdd());
				} else if (match(">>")) {
					node = new BinaryNode(">>", node, parseAdd());
				} else {
					return node;
				}
			}
		}

		private Node parseAdd() {
			Node node = parseMultiply();
			while (true) {
				if (match("+")) {
					node = new BinaryNode("+", node, parseMultiply());
				} else if (match("-")) {
					node = new BinaryNode("-", node, parseMultiply());
				} else {
					return node;
				}
			}
		}

		private Node parseMultiply() {
			Node node = parseUnary();
			while (true) {
				if (match("*")) {
					node = new BinaryNode("*", node, parseUnary());
				} else if (match("/")) {
					node = new BinaryNode("/", node, parseUnary());
				} else if (match("%")) {
					node = new BinaryNode("%", node, parseUnary());
				} else {
					return node;
				}
			}
		}

		private Node parseUnary() {
			if (match("++")) {
				String name = parseIdentifier().toLowerCase(Locale.ROOT);
				effects.variableWrite(name);
				return new CrementNode(name, 1, true);
			}
			if (match("--")) {
				String name = parseIdentifier().toLowerCase(Locale.ROOT);
				effects.variableWrite(name);
				return new CrementNode(name, -1, true);
			}
			if (match("+")) {
				return new UnaryNode("+", parseUnary());
			}
			if (match("-")) {
				return new UnaryNode("-", parseUnary());
			}
			if (match("!")) {
				return new UnaryNode("!", parseUnary());
			}
			if (match("~") && !source.startsWith("=", position)) {
				return new UnaryNode("~", parseUnary());
			}
			return parsePower();
		}

		private Node parsePower() {
			Node node = parsePostfix();
			if (match("^") || match("**")) {
				node = new BinaryNode("^", node, parseUnary());
			}
			return node;
		}

		private Node parsePostfix() {
			Node node = parsePrimary();
			while (true) {
				if (match("++")) {
					VariableNode variable = requireVariableNode(node, "post-increment");
					effects.variableWrite(variable.name());
					node = new CrementNode(variable.name(), 1, false);
				} else if (match("--")) {
					VariableNode variable = requireVariableNode(node, "post-decrement");
					effects.variableWrite(variable.name());
					node = new CrementNode(variable.name(), -1, false);
				} else if (match("!")) {
					node = new FactorialNode(node);
				} else {
					return node;
				}
			}
		}

		private Node parsePrimary() {
			skipWhitespace();
			if (match("(")) {
				Node node = parseExpression();
				expect(")");
				return node;
			}
			if (isAtEnd()) {
				throw error("Expected expression.");
			}

			char ch = source.charAt(position);
			if (Character.isDigit(ch) || ch == '.') {
				return parseNumber();
			}
			if (isIdentifierStart(ch)) {
				return parseIdentifierOrFunction();
			}
			throw error("Expected number, variable, function, or parenthesized expression.");
		}

		private Node parseNumber() {
			int start = position;
			boolean sawDigit = false;
			while (!isAtEnd() && Character.isDigit(source.charAt(position))) {
				position++;
				sawDigit = true;
			}
			if (!isAtEnd() && source.charAt(position) == '.') {
				position++;
				while (!isAtEnd() && Character.isDigit(source.charAt(position))) {
					position++;
					sawDigit = true;
				}
			}
			if (!sawDigit) {
				throw errorAt(start, "Invalid numeric literal.");
			}
			if (!isAtEnd() && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
				int exponentStart = position++;
				if (!isAtEnd() && (source.charAt(position) == '+' || source.charAt(position) == '-')) {
					position++;
				}
				boolean exponentDigit = false;
				while (!isAtEnd() && Character.isDigit(source.charAt(position))) {
					position++;
					exponentDigit = true;
				}
				if (!exponentDigit) {
					throw errorAt(exponentStart, "Invalid exponent in numeric literal.");
				}
			}
			try {
				return new LiteralNode(Double.parseDouble(source.substring(start, position)));
			} catch (NumberFormatException ex) {
				throw errorAt(start, "Invalid numeric literal.");
			}
		}

		private Node parseIdentifierOrFunction() {
			String name = parseIdentifier().toLowerCase(Locale.ROOT);
			if (match("(")) {
				List<Node> args = new ArrayList<>();
				if (!match(")")) {
					do {
						args.add(parseExpression());
					} while (match(","));
					expect(")");
				}
				markFunctionEffects(name, args);
				return new FunctionNode(name, List.copyOf(args));
			}
			effects.variableRead(name);
			return new VariableNode(name);
		}

		private void markFunctionEffects(String name, List<Node> args) {
			switch (name) {
				case "random", "randint" -> effects.random();
				case "perlin", "voronoi", "ridgedmulti" -> effects.noise();
				case "query", "queryabs", "queryrel" -> {
					effects.query();
					if (args.size() >= 5) {
						markQueryOutput(args.get(3));
						markQueryOutput(args.get(4));
					}
				}
				case "rotate", "swap" -> {
					effects.usesMutableState = true;
					for (Node arg : args) {
						if (arg instanceof VariableNode variable) {
							effects.variableWrite(variable.name());
						}
					}
				}
				default -> {
				}
			}
		}

		private void markQueryOutput(Node node) {
			if (node instanceof VariableNode variable) {
				effects.variableWrite(variable.name());
			}
		}

		private VariableNode requireVariableNode(Node node, String label) {
			if (node instanceof VariableNode variable) {
				return variable;
			}
			throw error(label + " requires a variable target.");
		}

		private String parseIdentifier() {
			skipWhitespace();
			if (isAtEnd() || !isIdentifierStart(source.charAt(position))) {
				throw error("Expected identifier.");
			}
			int start = position++;
			while (!isAtEnd() && isIdentifierPart(source.charAt(position))) {
				position++;
			}
			return source.substring(start, position);
		}

		private boolean matchKeyword(String keyword) {
			skipWhitespace();
			if (!source.regionMatches(true, position, keyword, 0, keyword.length())) {
				return false;
			}
			int end = position + keyword.length();
			if (end < source.length() && isIdentifierPart(source.charAt(end))) {
				return false;
			}
			if (position > 0 && isIdentifierPart(source.charAt(position - 1))) {
				return false;
			}
			position = end;
			return true;
		}

		private void expect(String token) {
			if (!match(token)) {
				throw error("Expected '" + token + "'.");
			}
		}

		private boolean match(String token) {
			skipWhitespace();
			if (!source.startsWith(token, position)) {
				return false;
			}
			position += token.length();
			return true;
		}

		private boolean peek(char token) {
			skipWhitespace();
			return !isAtEnd() && source.charAt(position) == token;
		}

		private void skipWhitespace() {
			while (!isAtEnd() && Character.isWhitespace(source.charAt(position))) {
				position++;
			}
		}

		private boolean isAtEnd() {
			return position >= source.length();
		}

		private IllegalArgumentException error(String message) {
			return errorAt(position, message);
		}

		private IllegalArgumentException errorAt(int index, String message) {
			return new IllegalArgumentException("Invalid mineagent_gen expression at character " + index + ": " + message);
		}
	}

	private static int floorToBlock(double value) {
		if (value > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		if (value < Integer.MIN_VALUE) {
			return Integer.MIN_VALUE;
		}
		return (int) Math.floor(value);
	}

	private static int roundToBlock(double value) {
		if (value > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		if (value < Integer.MIN_VALUE) {
			return Integer.MIN_VALUE;
		}
		return (int) Math.floor(value + 0.5D);
	}

	private static final class NoiseCache {
		private final Map<PerlinKey, PerlinNoise> perlin = new HashMap<>();

		private double perlin(double seed, double x, double y, double z, double frequency, double octaves, double persistence) {
			PerlinKey key = new PerlinKey((int) seed, frequency, Math.max(1, (int) octaves), persistence);
			return perlin.computeIfAbsent(key, PerlinNoise::new).noise(x, y, z);
		}

		private double ridgedMulti(double seed, double x, double y, double z, double frequency, double octaves) {
			PerlinKey key = new PerlinKey((int) seed, frequency, Math.max(1, (int) octaves), 0.5D);
			return perlin.computeIfAbsent(key, PerlinNoise::new).ridged(x, y, z);
		}

		private double voronoi(double seed, double x, double y, double z, double frequency) {
			double px = x * frequency;
			double py = y * frequency;
			double pz = z * frequency;
			int ix = floorToBlock(px);
			int iy = floorToBlock(py);
			int iz = floorToBlock(pz);
			double best = Double.MAX_VALUE;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						int cx = ix + dx;
						int cy = iy + dy;
						int cz = iz + dz;
						double fx = cx + hashUnit((int) seed, cx, cy, cz, 0);
						double fy = cy + hashUnit((int) seed, cx, cy, cz, 1);
						double fz = cz + hashUnit((int) seed, cx, cy, cz, 2);
						double dist = squared(px - fx, py - fy, pz - fz);
						best = Math.min(best, dist);
					}
				}
			}
			return Math.max(-1.0D, Math.min(1.0D, 1.0D - Math.sqrt(best) * 2.0D));
		}
	}

	private record PerlinKey(int seed, double frequency, int octaves, double persistence) {
	}

	private static final class PerlinNoise {
		private final PerlinKey key;

		private PerlinNoise(PerlinKey key) {
			this.key = key;
		}

		private double noise(double x, double y, double z) {
			double total = 0.0D;
			double amplitude = 1.0D;
			double max = 0.0D;
			double frequency = key.frequency();
			for (int octave = 0; octave < key.octaves(); octave++) {
				total += single(x * frequency, y * frequency, z * frequency, key.seed() + octave * 131) * amplitude;
				max += amplitude;
				amplitude *= key.persistence();
				frequency *= 2.0D;
			}
			return max == 0.0D ? 0.0D : total / max;
		}

		private double ridged(double x, double y, double z) {
			double total = 0.0D;
			double amplitude = 0.5D;
			double frequency = key.frequency();
			for (int octave = 0; octave < key.octaves(); octave++) {
				double ridge = 1.0D - Math.abs(single(x * frequency, y * frequency, z * frequency, key.seed() + octave * 131));
				total += ridge * ridge * amplitude;
				frequency *= 2.0D;
				amplitude *= 0.5D;
			}
			return Math.max(-1.0D, Math.min(1.0D, total * 2.0D - 1.0D));
		}

		private static double single(double x, double y, double z, int seed) {
			int x0 = floorToBlock(x);
			int y0 = floorToBlock(y);
			int z0 = floorToBlock(z);
			double xf = x - x0;
			double yf = y - y0;
			double zf = z - z0;
			double u = fade(xf);
			double v = fade(yf);
			double w = fade(zf);

			double x00 = lerp(grad(seed, x0, y0, z0, xf, yf, zf), grad(seed, x0 + 1, y0, z0, xf - 1, yf, zf), u);
			double x10 = lerp(grad(seed, x0, y0 + 1, z0, xf, yf - 1, zf), grad(seed, x0 + 1, y0 + 1, z0, xf - 1, yf - 1, zf), u);
			double x01 = lerp(grad(seed, x0, y0, z0 + 1, xf, yf, zf - 1), grad(seed, x0 + 1, y0, z0 + 1, xf - 1, yf, zf - 1), u);
			double x11 = lerp(grad(seed, x0, y0 + 1, z0 + 1, xf, yf - 1, zf - 1), grad(seed, x0 + 1, y0 + 1, z0 + 1, xf - 1, yf - 1, zf - 1), u);
			return lerp(lerp(x00, x10, v), lerp(x01, x11, v), w);
		}
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
	}

	private static double lerp(double a, double b, double t) {
		return a + t * (b - a);
	}

	private static double grad(int seed, int x, int y, int z, double dx, double dy, double dz) {
		return switch ((int) (mix(seed, x, y, z, 0) & 15L)) {
			case 0 -> dx + dy;
			case 1 -> -dx + dy;
			case 2 -> dx - dy;
			case 3 -> -dx - dy;
			case 4 -> dx + dz;
			case 5 -> -dx + dz;
			case 6 -> dx - dz;
			case 7 -> -dx - dz;
			case 8 -> dy + dz;
			case 9 -> -dy + dz;
			case 10 -> dy - dz;
			case 11 -> -dy - dz;
			case 12 -> dx + dy;
			case 13 -> -dx + dy;
			case 14 -> -dy + dz;
			default -> -dy - dz;
		};
	}

	private static double hashUnit(int seed, int x, int y, int z, int salt) {
		long mixed = mix(seed, x, y, z, salt);
		return ((mixed >>> 11) & ((1L << 53) - 1L)) / (double) (1L << 53);
	}

	private static long mix(int seed, int x, int y, int z, int salt) {
		long value = seed * 0x9E3779B97F4A7C15L
				^ x * 0xBF58476D1CE4E5B9L
				^ y * 0x94D049BB133111EBL
				^ z * 0x632BE59BD9B4E019L
				^ salt * 0x85157AF5L;
		value ^= value >>> 30;
		value *= 0xBF58476D1CE4E5B9L;
		value ^= value >>> 27;
		value *= 0x94D049BB133111EBL;
		value ^= value >>> 31;
		return value;
	}

	private static double squared(double x, double y, double z) {
		return x * x + y * y + z * z;
	}
}
