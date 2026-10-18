package com.nekyoh.nekylauncher.util;

/** Maven coordinate "group:artifact:version[:classifier][@ext]". */
public record Maven(String group, String artifact, String version, String classifier, String extension) {

    public static Maven parse(String coord) {
        String ext = "jar";
        String c = coord.trim();
        int at = c.indexOf('@');
        if (at >= 0) {
            ext = c.substring(at + 1);
            c = c.substring(0, at);
        }
        String[] p = c.split(":");
        if (p.length < 3) throw new IllegalArgumentException("Bad maven coordinate: " + coord);
        String classifier = p.length > 3 ? p[3] : null;
        return new Maven(p[0], p[1], p[2], classifier, ext);
    }

    /** Relative path using forward slashes, e.g. net/fabricmc/fabric-loader/0.15.0/fabric-loader-0.15.0.jar */
    public String path() {
        StringBuilder sb = new StringBuilder();
        sb.append(group.replace('.', '/')).append('/').append(artifact).append('/').append(version).append('/')
                .append(artifact).append('-').append(version);
        if (classifier != null && !classifier.isEmpty()) sb.append('-').append(classifier);
        sb.append('.').append(extension);
        return sb.toString();
    }

    public Maven withClassifier(String cls) { return new Maven(group, artifact, version, cls, extension); }

    /** Identity used for de-duplication across inherited profiles (version excluded). */
    public String key() {
        return group + ":" + artifact + (classifier != null ? ":" + classifier : "");
    }

    @Override
    public String toString() {
        return group + ":" + artifact + ":" + version + (classifier != null ? ":" + classifier : "") + ("jar".equals(extension) ? "" : "@" + extension);
    }
}
