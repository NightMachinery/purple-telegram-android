package org.telegram.messenger.purple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.telegram.tgnet.TLRPC;

public final class PurpleSyncAutoDownloadTest {
    private static final String SETTINGS = PurpleSyncPost.RECORD_FILE_NAME;
    private static final String PLAYLISTS = PurpleSyncPost.PLAYLISTS_RECORD_FILE_NAME;
    private static final String DOCUMENT_TYPE = "type = AUTODOWNLOAD_TYPE_DOCUMENT;";
    private static final int MESSAGE_CHECKS = 4;

    private static int checks;
    private static int failures;
    private static String section = "";

    private PurpleSyncAutoDownloadTest() {
    }

    private static void begin(String name) {
        section = name;
    }

    private static void check(boolean ok) {
        ++checks;
        if (!ok) {
            ++failures;
            final StackTraceElement where = new Throwable().getStackTrace()[1];
            System.out.println("  FAIL  " + section + ":" + where.getLineNumber());
        }
    }

    private static TLRPC.DocumentAttribute named(String name) {
        final TLRPC.TL_documentAttributeFilename attribute =
                new TLRPC.TL_documentAttributeFilename();
        attribute.file_name = name;
        return attribute;
    }

    private static TLRPC.Document document(TLRPC.DocumentAttribute... attributes) {
        final TLRPC.TL_document document = new TLRPC.TL_document();
        for (TLRPC.DocumentAttribute attribute : attributes) {
            document.attributes.add(attribute);
        }
        return document;
    }

    private static boolean excludes(String name) {
        return PurpleSyncAutoDownload.excludes(document(named(name)));
    }

    private static boolean coreCandidate(String fileName) {
        final PurpleSyncCore.Page page = PurpleSyncCore.classifyHistoryPage(0,
                new int[] { 5 }, new boolean[] { true }, new boolean[] { false },
                new boolean[] { true }, new String[] { "" },
                new String[] { fileName });
        check(page.isValid());
        return page.candidates.length == 1;
    }

    private static void testNamesMatchCore() {
        begin("names match the core");
        check("Purple settings sync.json".equals(SETTINGS));
        check("Purple playlists sync.json".equals(PLAYLISTS));
        check(coreCandidate(SETTINGS));
        check(coreCandidate(PLAYLISTS));
        check(!coreCandidate("settings.toml"));
    }

    private static void testRecords() {
        begin("records");
        check(excludes(SETTINGS));
        check(excludes(PLAYLISTS));
        final TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
        check(PurpleSyncAutoDownload.excludes(document(video, named(SETTINGS))));
        check(PurpleSyncAutoDownload.excludes(document(named("notes.txt"), named(PLAYLISTS))));
    }

    private static void testOtherDocuments() {
        begin("other documents");
        check(!excludes("settings.toml"));
        check(!excludes("notes.json"));
        check(!excludes("purple settings sync.json"));
        check(!excludes("Purple settings sync.json.bak"));
        check(!excludes(" Purple playlists sync.json"));
        check(!excludes(""));
        check(!excludes(null));
        final TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
        video.file_name = SETTINGS;
        check(!PurpleSyncAutoDownload.excludes(document(video)));
        check(!PurpleSyncAutoDownload.excludes(document()));
        final TLRPC.Document bare = document();
        bare.attributes = null;
        check(!PurpleSyncAutoDownload.excludes(bare));
        check(!PurpleSyncAutoDownload.excludes(null));
    }

    private static void testDownloadControllerGuards(String path) throws IOException {
        begin("DownloadController guards");
        final String source = new String(Files.readAllBytes(Paths.get(path)),
                StandardCharsets.UTF_8);
        int documents = 0;
        for (int at = source.indexOf(DOCUMENT_TYPE); at >= 0;
                at = source.indexOf(DOCUMENT_TYPE, at + 1)) {
            ++documents;
        }
        final Matcher guarded = Pattern.compile(
                "\\} else if \\((?<doc>[^\\n]+?) != null\\) \\{\\s*"
                        + "if \\(PurpleSyncAutoDownload\\.excludes\\(\\k<doc>\\)\\) \\{\\s*"
                        + "return 0;\\s*\\}\\s*"
                        + Pattern.quote(DOCUMENT_TYPE)).matcher(source);
        int guards = 0;
        while (guarded.find()) {
            ++guards;
        }
        check(documents == MESSAGE_CHECKS);
        check(guards == documents);
    }

    public static void main(String[] args) throws Exception {
        testNamesMatchCore();
        testRecords();
        testOtherDocuments();
        testDownloadControllerGuards(System.getProperty("purple.download.controller"));
        System.out.println("PurpleSyncAutoDownloadTest: " + checks + " checks, "
                + failures + " failures");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
