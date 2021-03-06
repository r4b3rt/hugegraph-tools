/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.cmd.manager;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.baidu.hugegraph.api.API;
import com.baidu.hugegraph.cmd.Printer;
import com.baidu.hugegraph.structure.constant.HugeType;
import com.baidu.hugegraph.structure.graph.Edge;
import com.baidu.hugegraph.structure.graph.Vertex;
import com.baidu.hugegraph.util.JsonUtil;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.ObjectMapper;

public class DumpGraphManager extends BackupManager {

    private static final int INIT_VERTEX_CAPACITY = 1_000_000;
    private static final int INIT_VERTEX_EDGES_CAPACITY = 10;

    private static final byte[] EOF = "\n".getBytes();

    private static volatile DumpFormatter dumpFormatter = null;

    private final JsonGraph graph;
    private final ObjectMapper mapper;

    public DumpGraphManager(String url, String graph) {
        super(url, graph);
        this.graph = new JsonGraph();
        this.mapper = this.client.mapper();
    }

    public static DumpFormatter dumpFormatter(DumpFormatter f) {
        DumpFormatter old = dumpFormatter;
        dumpFormatter = f;
        return old;
    }

    public void dump(String outputDir) {
        ensureDirectoryExist(outputDir);
        this.startTimer();

        // Fetch data to JsonGraph
        this.backupVertices(outputDir);
        this.backupEdges(outputDir);

        // Dump to file
        for (String table : this.graph.tables()) {
            File file = Paths.get(outputDir, table).toFile();
            this.submit(() -> dump(file, this.graph.table(table).values()));
        }

        this.shutdown(this.type());
        this.printSummary("dump graph");
    }

    private void dump(File file, Collection<JsonVertex> vertices) {
        try (FileOutputStream fos = new FileOutputStream(file);
             BufferedOutputStream bos = new BufferedOutputStream(fos);) {
            for (JsonVertex vertex : vertices) {
                String content = this.dump(vertex);
                bos.write(content.getBytes(API.CHARSET));
                bos.write(EOF);
            }
        } catch (Throwable e) {
            Printer.print("Failed to write vertex: %s", e);
        }
    }

    private String dump(JsonVertex vertex) throws Exception {
        if (dumpFormatter != null) {
            return dumpFormatter.dump(vertex);
        }
        // Write as json format by default
        return this.mapper.writeValueAsString(vertex);
    }

    @Override
    protected void write(String file, HugeType type, List<?> list) {
        switch (type) {
            case VERTEX:
                for (Object vertex : list) {
                    this.graph.put((Vertex) vertex);
                }
                break;
            case EDGE:
                for (Object edge : list) {
                    this.graph.put((Edge) edge);
                }
                break;
            default:
                throw new AssertionError("Invalid type " + type);
        }
    }

    public interface DumpFormatter {
        // Serialize a vertex(with edge and property) to string
        public String dump(JsonVertex vertex) throws Exception;
    }

    public static class JsonGraph {

        private Map<String, Map<Object, JsonVertex>> tables;

        public JsonGraph() {
            this.tables = new ConcurrentHashMap<>();
        }

        public Set<String> tables() {
            return this.tables.keySet();
        }

        public void put(Vertex vertex) {
            // Add vertex to table of `label`
            Map<Object, JsonVertex> vertices = this.table(vertex.label());
            vertices.put(vertex.id(), JsonVertex.from(vertex));
        }

        public void put(Edge edge) {
            // Find source vertex
            Map<Object, JsonVertex> vertices = this.table(edge.sourceLabel());
            assert vertices != null;
            JsonVertex source = vertices.get(edge.source());
            if (source == null) {
                Printer.print("Invalid edge without source vertex: %s", edge);
                return;
            }

            // Find target vertex
            vertices = this.table(edge.targetLabel());
            assert vertices != null;
            JsonVertex target = vertices.get(edge.target());
            if (target == null) {
                Printer.print("Invalid edge without target vertex: %s", edge);
                return;
            }

            // Add edge to source&target vertex
            JsonEdge jsonEdge = JsonEdge.from(edge);
            source.addEdge(jsonEdge);
            target.addEdge(jsonEdge);
        }

        private Map<Object, JsonVertex> table(String table) {
            Map<Object, JsonVertex> vertices = this.tables.get(table);
            if (vertices == null) {
                vertices = new ConcurrentHashMap<>(INIT_VERTEX_CAPACITY);
                this.tables.putIfAbsent(table, vertices);
            }
            return vertices;
        }
    }

    public static class JsonVertex {

        private Object id;
        private String label;
        private String properties;
        private List<JsonEdge> edges;

        public JsonVertex() {
            this.edges = new ArrayList<>(INIT_VERTEX_EDGES_CAPACITY);
        }

        public void addEdge(JsonEdge edge) {
            this.edges.add(edge);
        }

        public Object getId() {
            return this.id;
        }

        public String getLabel() {
            return this.label;
        }

        @JsonRawValue
        public String getProperties() {
            return this.properties;
        }

        public List<JsonEdge> getEdges() {
            return this.edges;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> properties() {
            return JsonUtil.fromJson(this.properties, Map.class);
        }

        public static JsonVertex from(Vertex v) {
            JsonVertex vertex = new JsonVertex();
            vertex.id = v.id();
            vertex.label = v.label();
            vertex.properties = JsonUtil.toJson(v.properties());
            return vertex;
        }
    }

    public static class JsonEdge {

        private String id;
        private String label;
        private Object source;
        private Object target;
        private String properties;

        public String getId() {
            return this.id;
        }

        public String getLabel() {
            return this.label;
        }

        public Object getSource() {
            return this.source;
        }

        public Object getTarget() {
            return this.target;
        }

        @JsonRawValue
        public String getProperties() {
            return this.properties;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> properties() {
            return JsonUtil.fromJson(this.properties, Map.class);
        }

        public static JsonEdge from(Edge e) {
            JsonEdge edge = new JsonEdge();
            edge.id = e.id();
            edge.label = e.label();
            edge.source = e.source();
            edge.target = e.target();
            edge.properties = JsonUtil.toJson(e.properties());
            return edge;
        }
    }
}
