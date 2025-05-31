package io.github.ecdcaeb.fabricintermediaryfetcher;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

public class FabricIntermediaryFetcher {
    private static final String MAVEN_METADATA_URL = "https://maven.fabricmc.net/net/fabricmc/intermediary/maven-metadata.xml";
    private static final String MAVEN_BASE_URL = "https://maven.fabricmc.net/net/fabricmc/intermediary/";
    private static final String OUTPUT_DIR = "intermediary_mappings";
    private static final int THREAD_COUNT = 5;
    private static final Pattern JSON_ESCAPE = Pattern.compile("[\"\\\\\b\f\n\r\t]");
    private static final String[] ESCAPE_CHARS = new String[128];

    static {
        // 初始化JSON转义字符映射
        for (int i = 0; i < 128; i++) {
            ESCAPE_CHARS[i] = null;
        }
        ESCAPE_CHARS['"'] = "\\\"";
        ESCAPE_CHARS['\\'] = "\\\\";
        ESCAPE_CHARS['\b'] = "\\b";
        ESCAPE_CHARS['\f'] = "\\f";
        ESCAPE_CHARS['\n'] = "\\n";
        ESCAPE_CHARS['\r'] = "\\r";
        ESCAPE_CHARS['\t'] = "\\t";
    }

    public static void main(String[] args) throws Exception {
        Path outputPath = Path.of(OUTPUT_DIR);
        Files.createDirectories(outputPath);

        List<String> versions = fetchAvailableVersions();
        System.out.println("找到 " + versions.size() + " 个 Intermediary 版本");

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        AtomicInteger processedCount = new AtomicInteger();

        for (String version : versions) {
            executor.submit(() -> {
                try {
                    processVersion(version);
                    int count = processedCount.incrementAndGet();
                    System.out.printf("Process: %d/%d - Version %s%n", count, versions.size(), version);
                } catch (Exception e) {
                    System.err.printf("Process Version %s Error!!: %s%n", version, e.getMessage());
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);
        System.out.println("Build Successful");
    }

    private static List<String> fetchAvailableVersions() throws IOException {
        URL url = new URL(MAVEN_METADATA_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            List<String> versions = new ArrayList<>();
            String line;
            boolean inVersioning = false;
            boolean inVersions = false;

            while ((line = reader.readLine()) != null) {
                if (line.contains("<versioning>")) inVersioning = true;
                if (line.contains("</versioning>")) inVersioning = false;

                if (inVersioning && line.contains("<versions>")) inVersions = true;
                if (inVersioning && line.contains("</versions>")) inVersions = false;

                if (inVersions && line.contains("<version>")) {
                    int start = line.indexOf("<version>") + 9;
                    int end = line.indexOf("</version>");
                    versions.add(line.substring(start, end));
                }
            }

            return versions;
        }
    }

    private static void processVersion(String version) throws Exception {
        String minecraftVersion = version.split("\\+")[0];
        String jarUrl = MAVEN_BASE_URL + version + "/intermediary-" + version + ".jar";
        Path tempJar = Files.createTempFile("intermediary", ".jar");

        try {
            downloadFile(jarUrl, tempJar);
            MapData mapData = extractMappings(tempJar);
            
            String fileName = sanitizeFileName(minecraftVersion) + ".json";
            Path jsonPath = Path.of(OUTPUT_DIR, fileName);
            
            try (BufferedWriter writer = Files.newBufferedWriter(jsonPath)) {
                writeMapDataAsJson(mapData, writer);
            }
        } finally {
            Files.deleteIfExists(tempJar);
        }
    }

    private static void downloadFile(String urlStr, Path outputPath) throws IOException {
        URL url = new URL(urlStr);
        try (InputStream in = url.openStream()) {
            Files.copy(in, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static MapData extractMappings(Path jarPath) throws IOException {
        MapData mapData = new MapData();

        try (JarInputStream jarStream = new JarInputStream(Files.newInputStream(jarPath))) {
            JarEntry entry;
            while ((entry = jarStream.getNextJarEntry()) != null) {
                if (entry.getName().equals("mappings/mappings.tiny")) {
                    MemoryMappingTree mappingTree = new MemoryMappingTree();
                    MappingReader.read(jarStream, mappingTree);

                    int srcNamespaceId = mappingTree.getNamespaceId("official");
                    int dstNamespaceId = mappingTree.getNamespaceId("intermediary");

                    mappingTree.getClasses().forEach(cls -> {
                        String srcName = cls.getName(srcNamespaceId);
                        String dstName = cls.getName(dstNamespaceId);
                        mapData.classes.put(srcName, dstName);

                        List<MethodMapping> methods = new ArrayList<>();
                        cls.getMethods().forEach(method -> {
                            String methodSrcName = method.getName(srcNamespaceId);
                            String methodDstName = method.getName(dstNamespaceId);
                            String descriptor = method.getDesc(srcNamespaceId);
                            methods.add(new MethodMapping(methodSrcName, methodDstName, descriptor));
                        });
                        if (!methods.isEmpty()) {
                            mapData.methods.put(srcName, methods);
                        }

                        List<FieldMapping> fields = new ArrayList<>();
                        cls.getFields().forEach(field -> {
                            String fieldSrcName = field.getName(srcNamespaceId);
                            String fieldDstName = field.getName(dstNamespaceId);
                            String descriptor = field.getDesc(srcNamespaceId);
                            fields.add(new FieldMapping(fieldSrcName, fieldDstName, descriptor));
                        });
                        if (!fields.isEmpty()) {
                            mapData.fields.put(srcName, fields);
                        }
                    });

                    break;
                }
            }
        }

        return mapData;
    }

    private static void writeMapDataAsJson(MapData mapData, BufferedWriter writer) throws IOException {
        writer.write("{");
        writer.newLine();
        
        writer.write("  \"classes\": {");
        writer.newLine();
        
        boolean firstClass = true;
        for (Map.Entry<String, String> entry : mapData.classes.entrySet()) {
            if (!firstClass) {
                writer.write(",");
                writer.newLine();
            }
            writer.write("    \"");
            writer.write(escapeJson(entry.getKey()));
            writer.write("\": \"");
            writer.write(escapeJson(entry.getValue()));
            writer.write("\"");
            firstClass = false;
        }
        
        writer.newLine();
        writer.write("  },");
        writer.newLine();
        
        writer.write("  \"methods\": {");
        writer.newLine();
        
        boolean firstClassMethod = true;
        for (Map.Entry<String, List<MethodMapping>> entry : mapData.methods.entrySet()) {
            if (!firstClassMethod) {
                writer.write(",");
                writer.newLine();
            }
            
            writer.write("    \"");
            writer.write(escapeJson(entry.getKey()));
            writer.write("\": [");
            writer.newLine();
            
            List<MethodMapping> methods = entry.getValue();
            for (int i = 0; i < methods.size(); i++) {
                MethodMapping method = methods.get(i);
                writer.write("      {");
                writer.newLine();
                writer.write("        \"srcName\": \"");
                writer.write(escapeJson(method.srcName));
                writer.write("\",");
                writer.newLine();
                writer.write("        \"dstName\": \"");
                writer.write(escapeJson(method.dstName));
                writer.write("\",");
                writer.newLine();
                writer.write("        \"descriptor\": \"");
                writer.write(escapeJson(method.descriptor));
                writer.write("\"");
                writer.newLine();
                writer.write("      }");
                
                if (i < methods.size() - 1) {
                    writer.write(",");
                }
                writer.newLine();
            }
            
            writer.write("    ]");
            firstClassMethod = false;
        }
        
        writer.newLine();
        writer.write("  },");
        writer.newLine();
        
        writer.write("  \"fields\": {");
        writer.newLine();
        
        boolean firstClassField = true;
        for (Map.Entry<String, List<FieldMapping>> entry : mapData.fields.entrySet()) {
            if (!firstClassField) {
                writer.write(",");
                writer.newLine();
            }
            
            writer.write("    \"");
            writer.write(escapeJson(entry.getKey()));
            writer.write("\": [");
            writer.newLine();
            
            List<FieldMapping> fields = entry.getValue();
            for (int i = 0; i < fields.size(); i++) {
                FieldMapping field = fields.get(i);
                writer.write("      {");
                writer.newLine();
                writer.write("        \"srcName\": \"");
                writer.write(escapeJson(field.srcName));
                writer.write("\",");
                writer.newLine();
                writer.write("        \"dstName\": \"");
                writer.write(escapeJson(field.dstName));
                writer.write("\",");
                writer.newLine();
                writer.write("        \"descriptor\": \"");
                writer.write(escapeJson(field.descriptor));
                writer.write("\"");
                writer.newLine();
                writer.write("      }");
                
                if (i < fields.size() - 1) {
                    writer.write(",");
                }
                writer.newLine();
            }
            
            writer.write("    ]");
            firstClassField = false;
        }
        
        writer.newLine();
        writer.write("  }");
        writer.newLine();
        writer.write("}");
    }

    private static String escapeJson(String input) {
        if (input == null) return "null";
        
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c < 128) {
                String escaped = ESCAPE_CHARS[c];
                if (escaped != null) {
                    sb.append(escaped);
                    continue;
                }
            } else if (c == '\u2028') {
                sb.append("\\u2028");
                continue;
            } else if (c == '\u2029') {
                sb.append("\\u2029");
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    static class MapData {
        Map<String, String> classes = new LinkedHashMap<>();
        Map<String, List<MethodMapping>> methods = new LinkedHashMap<>();
        Map<String, List<FieldMapping>> fields = new LinkedHashMap<>();
    }

    static class MethodMapping {
        String srcName;
        String dstName;
        String descriptor;

        public MethodMapping(String srcName, String dstName, String descriptor) {
            this.srcName = srcName;
            this.dstName = dstName;
            this.descriptor = descriptor;
        }
    }

    static class FieldMapping {
        String srcName;
        String dstName;
        String descriptor;

        public FieldMapping(String srcName, String dstName, String descriptor) {
            this.srcName = srcName;
            this.dstName = dstName;
            this.descriptor = descriptor;
        }
    }
}