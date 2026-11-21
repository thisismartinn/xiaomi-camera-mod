package io.martin.hypercameramod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Adds the "re-edit" data the Xiaomi Media Editor reads (the "edit" HEIF item plus the micamedit
// XMP marker) to a HEIC photo that has none, in memory. Pure Java, no Android classes.
//
// Layout, as written by the 18 Pro camera (read from its photos):
//  - an item of type "edit" holds a protobuf: field 3 { field 1 = the source image (a whole HEIC),
//    field 2 = one operation: name "filter", version "1.0", field 3 = filter{id, progress,
//    render type, colour parameter string, function}, field 11 = "micamera" };
//  - an XMP item (type "mime", application/rdf+xml) names it with
//    micamedit:version="1" micamedit:offset="-1" micamedit:len="<size of the edit item>".
//
// The 17 Ultra camera cannot give the unfiltered source image, so the source here is the saved
// photo itself and the operation is neutral (the filter at 0 %, every Palette value 0): the editor's
// Palette pad then starts at 0 and adjusts on top of the picture as it is.
//
// The HEIF boxes are edited by hand: two items are added to "iinf" and "iloc", the "meta" box grows,
// every file offset after it moves by the growth, and the new data goes into a new "mdat" box at
// the end. The result is read back and checked before it is returned.
final class ReEditInjector {
    static final int FILTER_STANDARD = 66048;   // the camera's id for "no filter"
    static final String NAMESPACE = "http://ns.xiaomi.com/photos/1.0/camera/reedit";

    private static final String RENDER_TYPE = "513";
    private static final String COLOR_PARAM_PREFIX = "@CubeLutEffect;cube_strength=0.0;lut_type=0;@";
    private static final String COLOR_PARAM_NEUTRAL = "@StyleAdjustEffect;Algos=0;SharpenIntensity=0.0;"
            + "ContrastIntensity=0.0;SaturationIntensity=0.0;Temperature=0.0;Tint=0.0;Grain=0.0;"
            + "VignetteIntensity=0.0";

    private static final int FTYP = cc("ftyp");
    private static final int META = cc("meta");
    private static final int MDAT = cc("mdat");
    private static final int ILOC = cc("iloc");
    private static final int IINF = cc("iinf");
    private static final int INFE = cc("infe");
    private static final int EDIT = cc("edit");
    private static final int MIME = cc("mime");

    private ReEditInjector() {
    }

    // The photo's own camera filter id, read from the camera's private JSON in the Exif item
    // ({"mirror":false,...,"filterId":655608,...}), or -1.
    static int cameraFilterId(byte[] file) {
        byte[] key = "\"filterId\":".getBytes(StandardCharsets.US_ASCII);
        int at = indexOf(file, key, file.length);
        if (at < 0) {
            return -1;
        }
        long value = 0;
        int digits = 0;
        for (int i = at + key.length; i < file.length && digits < 10; i++, digits++) {
            int c = file[i];
            if (c < '0' || c > '9') {
                break;
            }
            value = value * 10 + (c - '0');
        }
        return digits == 0 ? -1 : (int) Math.min(value, Integer.MAX_VALUE);
    }

    static boolean isHeic(byte[] file) {
        if (file == null || file.length < 24 || u32(file, 4) != FTYP) {
            return false;
        }
        int size = (int) Math.min(u32(file, 0), file.length);
        for (int p = 8; p + 4 <= size; p += 4) {
            int brand = (int) u32(file, p);
            if (brand == cc("heic") || brand == cc("mif1")) {
                return true;
            }
        }
        return false;
    }

    // Returns the photo with the re-edit data added, or null when it already has an "edit" item.
    // Throws IOException when the file is not laid out as expected; the caller then keeps the
    // original untouched.
    static byte[] inject(byte[] file, int editorFilterId, int progress) throws IOException {
        List<Box> top = boxes(file, 0, file.length);
        Box meta = null;
        for (Box box : top) {
            if (box.type == META && meta == null) {
                meta = box;
            }
            if (box.type == MDAT && meta == null) {
                throw new IOException("mdat before meta");
            }
        }
        if (meta == null) {
            throw new IOException("no meta box");
        }
        int metaBody = meta.start + meta.header + 4;   // after the full box version and flags
        List<Box> kids = boxes(file, metaBody, meta.end());
        Box iinf = null;
        Box iloc = null;
        for (Box kid : kids) {
            if (kid.type == IINF) {
                iinf = kid;
            } else if (kid.type == ILOC) {
                iloc = kid;
            }
        }
        if (iinf == null || iloc == null) {
            throw new IOException("no iinf or iloc box");
        }

        // ---- iinf ----
        int iinfVersion = file[iinf.start + iinf.header] & 0xFF;
        int countAt = iinf.start + iinf.header + 4;
        int countSize = iinfVersion == 0 ? 2 : 4;
        int entriesAt = countAt + countSize;
        List<Box> infes = boxes(file, entriesAt, iinf.end());
        int maxId = 0;
        int xmpId = -1;
        for (Box infe : infes) {
            if (infe.type != INFE) {
                throw new IOException("unexpected box in iinf");
            }
            int version = file[infe.start + infe.header] & 0xFF;
            if (version < 2) {
                continue;
            }
            int p = infe.start + infe.header + 4;
            int id;
            if (version == 2) {
                id = (int) u16(file, p);
                p += 2;
            } else {
                id = (int) u32(file, p);
                p += 4;
            }
            p += 2;   // protection index
            int itemType = (int) u32(file, p);
            p += 4;
            maxId = Math.max(maxId, id);
            if (itemType == EDIT) {
                return null;   // already has re-edit data
            }
            if (itemType == MIME && xmpId < 0) {
                p = skipCString(file, p, infe.end());   // item name
                String contentType = cString(file, p, infe.end());
                if (contentType.equals("application/rdf+xml")) {
                    xmpId = id;
                }
            }
        }
        if (maxId + 2 > 0xFFFF) {
            throw new IOException("item ids too large");
        }
        int editId = maxId + 1;
        int newXmpId = maxId + 2;

        // ---- iloc ----
        Iloc table = parseIloc(file, iloc);
        Item xmpItem = null;
        for (Item item : table.items) {
            if (item.id == xmpId) {
                xmpItem = item;
            }
        }
        if (xmpId >= 0 && (xmpItem == null || xmpItem.cm != 0 || xmpItem.extents.size() != 1)) {
            throw new IOException("existing XMP item is not a plain file extent");
        }

        // ---- the new data ----
        byte[] edit = buildEdit(file, editorFilterId, progress);
        byte[] xmp;
        if (xmpId >= 0) {
            Extent e = xmpItem.extents.get(0);
            byte[] old = Arrays.copyOfRange(file, (int) e.offset, (int) (e.offset + e.length));
            xmp = mergeXmp(old, edit.length);
        } else {
            xmp = newXmp(edit.length);
        }

        // ---- sizes first, because every offset after meta moves by the growth ----
        boolean addXmpItem = xmpId < 0;
        byte[] editInfe = infe(editId, "edit", null);
        byte[] xmpInfe = addXmpItem ? infe(newXmpId, "mime", "application/rdf+xml") : new byte[0];
        int iinfGrowth = editInfe.length + xmpInfe.length;
        int perEntry = table.entrySize();
        int ilocGrowth = perEntry * (addXmpItem ? 2 : 1);
        int growth = iinfGrowth + ilocGrowth;
        long metaEnd = meta.end();

        long newMdatStart = (long) file.length + growth;
        long editOffset = newMdatStart + 8;
        long xmpOffset = editOffset + edit.length;
        long newFileLength = xmpOffset + xmp.length;
        if (newFileLength > Integer.MAX_VALUE - 16) {
            throw new IOException("file too large");
        }

        // ---- iinf ----
        ByteArrayOutputStream newIinf = new ByteArrayOutputStream(iinf.size + iinfGrowth);
        newIinf.write(file, iinf.start, iinf.header + 4);   // box header, version and flags
        writeUInt(newIinf, infes.size() + (addXmpItem ? 2 : 1), countSize);
        newIinf.write(file, entriesAt, iinf.end() - entriesAt);
        newIinf.write(editInfe);
        newIinf.write(xmpInfe);
        byte[] iinfBytes = setBoxSize(newIinf.toByteArray(), iinf.header);

        // ---- iloc ----
        List<Item> items = new ArrayList<>();
        for (Item item : table.items) {
            Item copy = item.copy();
            if (copy.cm == 0) {
                for (Extent e : copy.extents) {
                    if (e.offset >= metaEnd) {
                        e.offset += growth;
                    }
                }
            }
            if (copy.id == xmpId) {
                Extent e = copy.extents.get(0);
                e.offset = xmpOffset;
                e.length = xmp.length;
            }
            items.add(copy);
        }
        items.add(Item.file(editId, editOffset, edit.length));
        if (addXmpItem) {
            items.add(Item.file(newXmpId, xmpOffset, xmp.length));
        }
        byte[] ilocBytes = setBoxSize(table.write(file, iloc, items), iloc.header);

        // ---- meta ----
        ByteArrayOutputStream newMeta = new ByteArrayOutputStream(meta.size + growth);
        newMeta.write(file, meta.start, meta.header + 4);
        for (Box kid : kids) {
            if (kid == iinf) {
                newMeta.write(iinfBytes);
            } else if (kid == iloc) {
                newMeta.write(ilocBytes);
            } else {
                newMeta.write(file, kid.start, kid.size);
            }
        }
        byte[] metaBytes = setBoxSize(newMeta.toByteArray(), meta.header);
        if (metaBytes.length != meta.size + growth) {
            throw new IOException("meta size mismatch " + metaBytes.length + " vs "
                    + (meta.size + growth));
        }

        // ---- the file ----
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) newFileLength);
        out.write(file, 0, meta.start);
        out.write(metaBytes);
        out.write(file, meta.end(), file.length - meta.end());
        writeUInt(out, 8L + edit.length + xmp.length, 4);
        out.write(new byte[]{'m', 'd', 'a', 't'});
        out.write(edit);
        out.write(xmp);
        byte[] result = out.toByteArray();
        if (result.length != newFileLength) {
            throw new IOException("size mismatch");
        }
        verify(file, result, editId, edit, xmpId >= 0 ? xmpId : newXmpId, xmp, metaEnd, growth);
        return result;
    }

    // ---- what the editor reads ---------------------------------------------------------------

    private static byte[] buildEdit(byte[] image, int filterId, int progress) throws IOException {
        ByteArrayOutputStream filter = new ByteArrayOutputStream();
        putVarint(filter, 1, filterId);
        putVarint(filter, 2, progress);
        putBytes(filter, 3, RENDER_TYPE.getBytes(StandardCharsets.UTF_8));
        putBytes(filter, 4, (COLOR_PARAM_PREFIX + COLOR_PARAM_NEUTRAL)
                .getBytes(StandardCharsets.UTF_8));
        putBytes(filter, 5, "APPLY_FILTER".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream op = new ByteArrayOutputStream();
        putBytes(op, 1, "filter".getBytes(StandardCharsets.UTF_8));
        putBytes(op, 2, "1.0".getBytes(StandardCharsets.UTF_8));
        putBytes(op, 3, filter.toByteArray());
        putBytes(op, 11, "micamera".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream inner = new ByteArrayOutputStream(image.length + 256);
        putBytes(inner, 1, image);
        putBytes(inner, 2, op.toByteArray());

        ByteArrayOutputStream outer = new ByteArrayOutputStream(inner.size() + 16);
        putBytes(outer, 3, inner.toByteArray());
        return outer.toByteArray();
    }

    private static byte[] newXmp(int editLength) {
        return ("<x:xmpmeta\n"
                + "  xmlns:x=\"adobe:ns:meta/\"\n"
                + "  x:xmptk=\"Adobe XMP Core 5.1.2\">\n"
                + "  <rdf:RDF\n"
                + "    xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + descriptionFor(editLength)
                + "  </rdf:RDF>\n"
                + "</x:xmpmeta>\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String descriptionFor(int editLength) {
        return "    <rdf:Description\n"
                + "      rdf:about=\"\"\n"
                + "      xmlns:micamedit=\"" + NAMESPACE + "\"\n"
                + "      micamedit:version=\"1\"\n"
                + "      micamedit:offset=\"-1\"\n"
                + "      micamedit:len=\"" + editLength + "\"/>\n";
    }

    private static byte[] mergeXmp(byte[] old, int editLength) throws IOException {
        String text = new String(old, StandardCharsets.UTF_8);
        if (text.contains("micamedit")) {
            throw new IOException("XMP already mentions micamedit");
        }
        int close = text.lastIndexOf("</rdf:RDF>");
        if (close < 0) {
            throw new IOException("XMP has no rdf:RDF element");
        }
        return (text.substring(0, close) + descriptionFor(editLength) + text.substring(close))
                .getBytes(StandardCharsets.UTF_8);
    }

    // ---- HEIF boxes --------------------------------------------------------------------------

    private static final class Box {
        final int type;
        final int start;
        final int header;
        final int size;

        Box(int type, int start, int header, int size) {
            this.type = type;
            this.start = start;
            this.header = header;
            this.size = size;
        }

        int end() {
            return start + size;
        }
    }

    private static List<Box> boxes(byte[] d, int from, int to) throws IOException {
        List<Box> out = new ArrayList<>();
        int p = from;
        while (p + 8 <= to) {
            long size = u32(d, p);
            int type = (int) u32(d, p + 4);
            int header = 8;
            if (size == 1) {
                if (p + 16 > to) {
                    throw new IOException("short box header at " + p);
                }
                size = u64(d, p + 8);
                header = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < header || p + size > to) {
                throw new IOException("bad box size at " + p);
            }
            out.add(new Box(type, p, header, (int) size));
            p += (int) size;
        }
        if (p != to) {
            throw new IOException("trailing bytes in box list");
        }
        return out;
    }

    private static byte[] setBoxSize(byte[] box, int header) {
        if (header == 8) {
            putU32(box, 0, box.length);
        } else {
            putU64(box, 8, box.length);
        }
        return box;
    }

    private static byte[] infe(int id, String type, String contentType) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(new byte[]{2, 0, 0, 1});   // version 2; flags 1 (hidden), as the camera writes them
        writeUInt(body, id, 2);
        writeUInt(body, 0, 2);                // protection index
        body.write(type.getBytes(StandardCharsets.US_ASCII));
        body.write(0);                        // item name ""
        if (contentType != null) {
            body.write(contentType.getBytes(StandardCharsets.US_ASCII));
            body.write(0);
            body.write(0);                    // content encoding ""
        }
        ByteArrayOutputStream box = new ByteArrayOutputStream();
        writeUInt(box, 8L + body.size(), 4);
        box.write(new byte[]{'i', 'n', 'f', 'e'});
        box.write(body.toByteArray());
        return box.toByteArray();
    }

    private static final class Extent {
        long index;
        long offset;
        long length;
    }

    private static final class Item {
        int id;
        int cmField;   // the 16 bits that hold the reserved bits and the construction method
        int cm;
        int dataRef;
        List<Extent> extents = new ArrayList<>();

        Item copy() {
            Item c = new Item();
            c.id = id;
            c.cmField = cmField;
            c.cm = cm;
            c.dataRef = dataRef;
            for (Extent e : extents) {
                Extent n = new Extent();
                n.index = e.index;
                n.offset = e.offset;
                n.length = e.length;
                c.extents.add(n);
            }
            return c;
        }

        static Item file(int id, long offset, long length) {
            Item item = new Item();
            item.id = id;
            Extent e = new Extent();
            e.offset = offset;
            e.length = length;
            item.extents.add(e);
            return item;
        }
    }

    private static final class Iloc {
        int version;
        int offsetSize;
        int lengthSize;
        int baseOffsetSize;
        int indexSize;
        List<Item> items = new ArrayList<>();

        int idSize() {
            return version < 2 ? 2 : 4;
        }

        // bytes of one entry with one extent (the entries added here)
        int entrySize() {
            return idSize() + (version >= 1 ? 2 : 0) + 2 + baseOffsetSize + 2
                    + (version >= 1 ? indexSize : 0) + offsetSize + lengthSize;
        }

        byte[] write(byte[] file, Box box, List<Item> all) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(file, box.start, box.header + 4);   // box header, version and flags
            out.write((offsetSize << 4) | lengthSize);
            out.write((baseOffsetSize << 4) | (version >= 1 ? indexSize : 0));
            writeUInt(out, all.size(), version < 2 ? 2 : 4);
            for (Item item : all) {
                writeUInt(out, item.id, idSize());
                if (version >= 1) {
                    writeUInt(out, item.cmField, 2);
                }
                writeUInt(out, item.dataRef, 2);
                writeUInt(out, 0, baseOffsetSize);
                writeUInt(out, item.extents.size(), 2);
                for (Extent e : item.extents) {
                    if (version >= 1 && indexSize > 0) {
                        writeUInt(out, e.index, indexSize);
                    }
                    writeUInt(out, e.offset, offsetSize);
                    writeUInt(out, e.length, lengthSize);
                }
            }
            return out.toByteArray();
        }
    }

    private static Iloc parseIloc(byte[] d, Box box) throws IOException {
        Iloc t = new Iloc();
        int p = box.start + box.header;
        t.version = d[p] & 0xFF;
        if (t.version > 2) {
            throw new IOException("unsupported iloc version " + t.version);
        }
        p += 4;
        t.offsetSize = (d[p] >> 4) & 0xF;
        t.lengthSize = d[p] & 0xF;
        t.baseOffsetSize = (d[p + 1] >> 4) & 0xF;
        t.indexSize = t.version >= 1 ? d[p + 1] & 0xF : 0;
        p += 2;
        if ((t.offsetSize != 4 && t.offsetSize != 8) || (t.lengthSize != 4 && t.lengthSize != 8)
                || t.baseOffsetSize != 0 || (t.indexSize != 0 && t.indexSize != 4
                && t.indexSize != 8)) {
            throw new IOException("unsupported iloc field sizes");
        }
        long count;
        if (t.version < 2) {
            count = u16(d, p);
            p += 2;
        } else {
            count = u32(d, p);
            p += 4;
        }
        for (long i = 0; i < count; i++) {
            Item item = new Item();
            if (t.version < 2) {
                item.id = (int) u16(d, p);
                p += 2;
            } else {
                item.id = (int) u32(d, p);
                p += 4;
            }
            if (t.version >= 1) {
                item.cmField = (int) u16(d, p);
                item.cm = item.cmField & 0xF;
                p += 2;
            }
            item.dataRef = (int) u16(d, p);
            p += 2;
            long extents = u16(d, p);
            p += 2;
            for (long k = 0; k < extents; k++) {
                Extent e = new Extent();
                if (t.version >= 1 && t.indexSize > 0) {
                    e.index = uN(d, p, t.indexSize);
                    p += t.indexSize;
                }
                e.offset = uN(d, p, t.offsetSize);
                p += t.offsetSize;
                e.length = uN(d, p, t.lengthSize);
                p += t.lengthSize;
                item.extents.add(e);
            }
            t.items.add(item);
        }
        if (p != box.end()) {
            throw new IOException("iloc size mismatch");
        }
        return t;
    }

    // ---- check the result ---------------------------------------------------------------------

    private static void verify(byte[] before, byte[] after, int editId, byte[] edit, int xmpId,
                               byte[] xmp, long metaEnd, int growth) throws IOException {
        List<Box> top = boxes(after, 0, after.length);
        Box meta = null;
        for (Box box : top) {
            if (box.type == META) {
                meta = box;
            }
        }
        if (meta == null) {
            throw new IOException("verify: no meta");
        }
        List<Box> kids = boxes(after, meta.start + meta.header + 4, meta.end());
        Box iloc = null;
        for (Box kid : kids) {
            if (kid.type == ILOC) {
                iloc = kid;
            }
        }
        if (iloc == null) {
            throw new IOException("verify: no iloc");
        }
        Iloc now = parseIloc(after, iloc);
        List<Box> oldTop = boxes(before, 0, before.length);
        Box oldMeta = null;
        for (Box box : oldTop) {
            if (box.type == META) {
                oldMeta = box;
            }
        }
        Iloc was = parseIloc(before, kidsOf(before, oldMeta, ILOC));
        boolean sawEdit = false;
        boolean sawXmp = false;
        for (Item item : now.items) {
            if (item.cm == 0) {
                for (Extent e : item.extents) {
                    if (e.offset < 0 || e.offset + e.length > after.length) {
                        throw new IOException("verify: extent outside the file, item " + item.id);
                    }
                }
            }
            if (item.id == editId) {
                sawEdit = equalAt(after, item, edit);
            } else if (item.id == xmpId) {
                sawXmp = equalAt(after, item, xmp);
            }
        }
        if (!sawEdit || !sawXmp) {
            throw new IOException("verify: new items not readable (edit " + sawEdit + ", xmp "
                    + sawXmp + ")");
        }
        // every original item still reads the same bytes at its new place
        for (Item old : was.items) {
            if (old.id == xmpId || old.cm != 0) {
                continue;
            }
            Item moved = null;
            for (Item item : now.items) {
                if (item.id == old.id) {
                    moved = item;
                }
            }
            if (moved == null || moved.extents.size() != old.extents.size()) {
                throw new IOException("verify: item " + old.id + " lost");
            }
            for (int i = 0; i < old.extents.size(); i++) {
                Extent a = old.extents.get(i);
                Extent b = moved.extents.get(i);
                if (a.length != b.length || !Arrays.equals(
                        Arrays.copyOfRange(before, (int) a.offset, (int) (a.offset + a.length)),
                        Arrays.copyOfRange(after, (int) b.offset, (int) (b.offset + b.length)))) {
                    throw new IOException("verify: item " + old.id + " changed");
                }
            }
        }
    }

    private static Box kidsOf(byte[] d, Box meta, int type) throws IOException {
        for (Box kid : boxes(d, meta.start + meta.header + 4, meta.end())) {
            if (kid.type == type) {
                return kid;
            }
        }
        throw new IOException("verify: no box");
    }

    private static boolean equalAt(byte[] d, Item item, byte[] expected) {
        if (item.extents.size() != 1) {
            return false;
        }
        Extent e = item.extents.get(0);
        if (e.length != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (d[(int) e.offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    // ---- small helpers ------------------------------------------------------------------------

    private static void putVarint(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, ((long) field << 3));
        writeVarint(out, value);
    }

    private static void putBytes(ByteArrayOutputStream out, int field, byte[] data) {
        writeVarint(out, ((long) field << 3) | 2);
        writeVarint(out, data.length);
        out.write(data, 0, data.length);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void writeUInt(ByteArrayOutputStream out, long value, int size) throws IOException {
        if (size < 8 && value >= (1L << (8 * size)) || value < 0) {
            throw new IOException("value " + value + " does not fit " + size + " bytes");
        }
        for (int i = size - 1; i >= 0; i--) {
            out.write((int) (value >>> (8 * i)) & 0xFF);
        }
    }

    private static int skipCString(byte[] d, int p, int end) throws IOException {
        while (p < end && d[p] != 0) {
            p++;
        }
        if (p >= end) {
            throw new IOException("unterminated string");
        }
        return p + 1;
    }

    private static String cString(byte[] d, int p, int end) {
        int q = p;
        while (q < end && d[q] != 0) {
            q++;
        }
        return new String(d, p, q - p, StandardCharsets.US_ASCII);
    }

    private static int indexOf(byte[] d, byte[] key, int limit) {
        outer:
        for (int i = 0; i + key.length <= limit; i++) {
            for (int j = 0; j < key.length; j++) {
                if (d[i + j] != key[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int cc(String s) {
        return ((s.charAt(0) & 0xFF) << 24) | ((s.charAt(1) & 0xFF) << 16)
                | ((s.charAt(2) & 0xFF) << 8) | (s.charAt(3) & 0xFF);
    }

    private static long u16(byte[] d, int p) {
        return ((d[p] & 0xFFL) << 8) | (d[p + 1] & 0xFFL);
    }

    private static long u32(byte[] d, int p) {
        return ((d[p] & 0xFFL) << 24) | ((d[p + 1] & 0xFFL) << 16) | ((d[p + 2] & 0xFFL) << 8)
                | (d[p + 3] & 0xFFL);
    }

    private static long u64(byte[] d, int p) {
        return (u32(d, p) << 32) | u32(d, p + 4);
    }

    private static long uN(byte[] d, int p, int size) {
        return size == 4 ? u32(d, p) : size == 8 ? u64(d, p) : 0;
    }

    private static void putU32(byte[] d, int p, long v) {
        d[p] = (byte) (v >>> 24);
        d[p + 1] = (byte) (v >>> 16);
        d[p + 2] = (byte) (v >>> 8);
        d[p + 3] = (byte) v;
    }

    private static void putU64(byte[] d, int p, long v) {
        putU32(d, p, v >>> 32);
        putU32(d, p + 4, v & 0xFFFFFFFFL);
    }
}
