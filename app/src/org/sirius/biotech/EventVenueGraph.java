package org.sirius.biotech;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/** MV floor-2 grid: absolute metres, Y up, strict closures and no corner cutting. */
final class EventVenueGraph {
    static final class Node {
        final String id;
        final float x, y;
        Node(String id, float x, float y) { this.id = id; this.x = x; this.y = y; }
    }
    static final class Route {
        final List<Node> nodes;
        final double distance;
        Route(List<Node> nodes, double distance) { this.nodes = nodes; this.distance = distance; }
    }
    private static final class Visit implements Comparable<Visit> {
        final int cell;
        final double cost, estimate;
        Visit(int cell, double cost, double estimate) { this.cell = cell; this.cost = cost; this.estimate = estimate; }
        @Override public int compareTo(Visit other) {
            int c = Double.compare(estimate, other.estimate);
            if (c == 0) c = Double.compare(cost, other.cost);
            return c == 0 ? Integer.compare(cell, other.cell) : c;
        }
    }
    final Map<String, Node> nodes = new HashMap<>();
    private final BitSet open = new BitSet();
    private int cols, rows;
    private double x0, y1, resolution;
    void configure(int cols, int rows, double x0, double y1, double resolution) {
        if (cols <= 0 || rows <= 0 || (long) cols * rows > Integer.MAX_VALUE || !(resolution > 0))
            throw new IllegalArgumentException("Invalid grid");
        this.cols = cols; this.rows = rows; this.x0 = x0; this.y1 = y1; this.resolution = resolution;
        open.clear(); nodes.clear();
    }
    void addRun(int row, int start, int end) {
        if (row < 0 || row >= rows || start < 0 || end > cols || end <= start)
            throw new IllegalArgumentException("Invalid grid run");
        open.set(row * cols + start, row * cols + end);
    }
    boolean isOpen(int cell) { return cell >= 0 && cell < cols * rows && open.get(cell); }
    Node node(int cell) {
        return new Node(Integer.toString(cell), (float) (x0 + (cell % cols + .5) * resolution),
                (float) (y1 - (cell / cols + .5) * resolution));
    }
    void addEntrance(int cell) {
        if (!isOpen(cell)) throw new IllegalArgumentException("Closed entrance");
        Node n = node(cell); nodes.put(n.id, n);
    }
    Route route(String from, String to) { return route(from, to, () -> false); }
    Route route(String from, String to, BooleanSupplier cancelled) {
        if (from == null || to == null) return null;
        if (!nodes.containsKey(from) || !nodes.containsKey(to)) throw new IllegalArgumentException("Unknown entrance");
        int start = Integer.parseInt(from), goal = Integer.parseInt(to);
        Map<Integer, Double> costs = new HashMap<>();
        Map<Integer, Integer> previous = new HashMap<>();
        PriorityQueue<Visit> queue = new PriorityQueue<>();
        costs.put(start, 0d); queue.add(new Visit(start, 0, heuristic(start, goal)));
        int[] dr = {-1, 1, 0, 0, -1, -1, 1, 1};
        int[] dc = {0, 0, -1, 1, -1, 1, -1, 1};
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) return null;
            Visit v = queue.remove();
            if (v.cost > costs.get(v.cell)) continue;
            if (v.cell == goal) {
                ArrayList<Node> path = new ArrayList<>();
                for (int c = goal;; c = previous.get(c)) { path.add(node(c)); if (c == start) break; }
                Collections.reverse(path);
                return new Route(path, v.cost * resolution);
            }
            int r = v.cell / cols, c = v.cell % cols;
            for (int i = 0; i < 8; i++) {
                int nr = r + dr[i], nc = c + dc[i];
                if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) continue;
                int next = nr * cols + nc;
                if (!isOpen(next)) continue;
                if (i >= 4 && (!isOpen(r * cols + nc) || !isOpen(nr * cols + c))) continue;
                double cost = v.cost + (i < 4 ? 1 : Math.sqrt(2));
                if (cost < costs.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    costs.put(next, cost); previous.put(next, v.cell);
                    queue.add(new Visit(next, cost, cost + heuristic(next, goal)));
                }
            }
        }
        return null;
    }
    private double heuristic(int a, int b) {
        return Math.hypot(a % cols - b % cols, a / cols - b / cols);
    }
}
