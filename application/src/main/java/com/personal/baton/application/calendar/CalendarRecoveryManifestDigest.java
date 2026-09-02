package com.personal.baton.application.calendar;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class CalendarRecoveryManifestDigest {

    private CalendarRecoveryManifestDigest() {
    }

    static String snapshot(CalendarSnapshot snapshot) {
        return digest(output -> {
            writeString(output, "baton-cal-snapshot-v1");
            writeString(output, snapshot.sourceItemId().toString());
            writeString(output, snapshot.seasonId().toString());
            output.writeInt(snapshot.revision());
            writeString(output, snapshot.status().name());
            writeString(output, snapshot.summary());
            writeNullableString(output, snapshot.description());
            writeNullableString(output, snapshot.location());
            writeString(output, snapshot.sourceUpdatedAt().toString());
            switch (snapshot.time()) {
                case CalendarSnapshot.UtcPoint point -> {
                    writeString(output, "UTC_POINT");
                    writeString(output, point.at().toString());
                }
                case CalendarSnapshot.ZonedLocalPoint point -> {
                    writeString(output, "ZONED_LOCAL_POINT");
                    writeString(output, point.at().toString());
                    writeString(output, point.zoneId());
                }
                case CalendarSnapshot.AllDay allDay -> {
                    writeString(output, "ALL_DAY");
                    writeString(output, allDay.startDate().toString());
                    writeString(output, allDay.endDate().toString());
                }
            }
        });
    }

    static String items(List<CalendarSnapshot> snapshots) {
        return digest(output -> {
            writeString(output, "baton-cal-recovery-items-v1");
            for (var snapshot : snapshots.stream()
                    .sorted(Comparator.comparing(value -> value.sourceItemId().toString()))
                    .toList()) {
                writeString(output, snapshot.sourceItemId().toString());
                output.writeInt(snapshot.revision());
                writeString(output, snapshot(snapshot));
            }
        });
    }

    static String metadata(CalendarSeasonMetadata metadata) {
        return digest(output -> {
            writeString(output, "baton-cal-recovery-metadata-v1");
            output.writeInt(metadata.revision());
            writeString(output, metadata.displayName());
        });
    }

    static String seasons(List<CalendarRecoveryManifest.Season> seasons) {
        return digest(output -> {
            writeString(output, "baton-cal-recovery-seasons-v1");
            for (var season : seasons.stream()
                    .sorted(Comparator.comparing(value -> value.seasonId().toString()))
                    .toList()) {
                writeString(output, season.seasonId().toString());
                output.writeInt(season.itemCount());
                writeString(output, season.itemDigest());
                output.writeBoolean(true);
                output.writeInt(season.metadataRevision());
                writeString(output, season.metadataDigest());
            }
        });
    }

    private static String digest(OutputWriter writer) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var output = new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest)
            )) {
                writer.write(output);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("CAL 복구 다이제스트를 계산할 수 없습니다", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeNullableString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
        } else {
            writeString(output, value);
        }
    }

    @FunctionalInterface
    private interface OutputWriter {
        void write(DataOutputStream output) throws IOException;
    }
}
