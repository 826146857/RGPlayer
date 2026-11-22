package com.rg.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 目录树节点（由 MediaStore 的 RELATIVE_PATH 构建）。 */
public class DirNode {
    public final String path;   // 例如 "Movies/1"，根为 ""
    public final String name;   // 最后一段，根为 "视频库"
    public final Map<String, DirNode> children = new LinkedHashMap<>();
    public final List<VideoItem> videos = new ArrayList<>();

    public DirNode(String path, String name) {
        this.path = path;
        this.name = name;
    }

    public int count() {
        int n = videos.size();
        for (DirNode c : children.values()) n += c.count();
        return n;
    }

    public static DirNode build(List<VideoItem> videos) {
        DirNode root = new DirNode("", "视频库");
        for (VideoItem v : videos) {
            String dir = v.relPath == null ? "" : v.relPath.replace('\\', '/');
            while (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
            String[] segs = dir.isEmpty() ? new String[0] : dir.split("/");
            DirNode node = root;
            StringBuilder sb = new StringBuilder();
            for (String s : segs) {
                if (s.isEmpty()) continue;
                if (sb.length() > 0) sb.append('/');
                sb.append(s);
                DirNode child = node.children.get(s);
                if (child == null) {
                    child = new DirNode(sb.toString(), s);
                    node.children.put(s, child);
                }
                node = child;
            }
            node.videos.add(v);
        }
        return root;
    }

    public DirNode find(String path) {
        if (path == null || path.isEmpty()) return this;
        DirNode node = this;
        for (String s : path.split("/")) {
            if (s.isEmpty()) continue;
            node = node.children.get(s);
            if (node == null) return this;
        }
        return node;
    }

    /** 首页入口：视频所在目录的上一级；顶层目录直接作为入口。 */
    public List<DirNode> homeEntries() {
        Map<String, DirNode> out = new LinkedHashMap<>();
        walk(this, out);
        return new ArrayList<>(out.values());
    }

    private void walk(DirNode node, Map<String, DirNode> out) {
        if (!node.videos.isEmpty()) {
            String parentPath = parentPath(node.path);
            if (parentPath.isEmpty()) {
                out.put(node.path, node);
            } else {
                DirNode parent = find(parentPath);
                out.put(parent.path, parent);
            }
        }
        for (DirNode c : node.children.values()) walk(c, out);
    }

    private static String parentPath(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }
}