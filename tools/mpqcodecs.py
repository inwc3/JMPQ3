"""Huffman and ADPCM decompression, transcribed from StormLib.

These are the two codecs a Warcraft III ``.wav`` is stored with, and the reason
this file exists is issue #11: attributes generation was disabled years ago
because the CRC32 of some wav files disagreed with StormLib's. A CRC32 over
decoded content can only disagree if the decode disagrees, so the only way to
settle it is to decode the same file twice, independently.

"Independently" is the whole point. This is a transcription of
``src/huffman/huff.cpp`` and ``src/adpcm/adpcm.cpp`` from StormLib, written from
the C and not from the Java in this repository. If it agreed with the Java by
construction it would prove nothing -- that trap has already cost this project
two real bugs, both of which a reader and a writer sharing a misconception
agreed about perfectly.

Deliberately omitted: the quick-link cache in ``DecodeOneByte``. It is a lookup
table that short-circuits the first seven levels of tree descent and cannot
change the value produced, only how fast it is produced. Leaving it out keeps
this readable, which matters more here than speed.
"""

from huffman_tables import DATA_DISTRIBUTIONS, DATA_TYPE_SPARSE

MAX_DATA_TYPE = len(DATA_DISTRIBUTIONS)

# Sentinels that live in the tree alongside the 256 byte values.
END_OF_STREAM = 0x100
NEW_BYTE_ESCAPE = 0x101


class HuffmanError(Exception):
    """The stream could not be decoded."""


class _BitReader:
    """StormLib's TInputStream: little-endian, least-significant bit first."""

    def __init__(self, data):
        self.data = data
        self.at = 0
        self.buffer = 0
        self.count = 0

    def bit(self):
        if self.count == 0:
            if self.at >= len(self.data):
                return None
            self.buffer = self.data[self.at]
            self.at += 1
            self.count = 8
        value = self.buffer & 0x01
        self.buffer >>= 1
        self.count -= 1
        return value

    def byte(self):
        if self.count < 8:
            if self.at >= len(self.data):
                return None
            self.buffer |= self.data[self.at] << self.count
            self.at += 1
            self.count += 8
        value = self.buffer & 0xFF
        self.buffer >>= 8
        self.count -= 8
        return value


class _Item:
    """One node, which is simultaneously a tree node and a list element.

    StormLib keeps every node in one weight-sorted doubly-linked list, and a
    node's two children are ``child_lo`` and ``child_lo.prev``. The list order
    *is* the tree structure; that is why the rebalancing below moves list links
    around rather than swapping child pointers.
    """

    __slots__ = ("value", "weight", "parent", "child_lo", "prev", "next")

    def __init__(self, value=0, weight=0):
        self.value = value
        self.weight = weight
        self.parent = None
        self.child_lo = None
        self.prev = self
        self.next = self

    def unlink(self):
        if self.prev is not None and self.next is not None:
            self.prev.next = self.next
            self.next.prev = self.prev
        self.prev = None
        self.next = None


class HuffmanTree:
    """StormLib's THuffmannTree, decompression side only."""

    def __init__(self):
        self.head = _Item()
        self.by_byte = {}
        self.sparse = False

    # -- list plumbing, mirroring LinkTwoItems / InsertItem -----------------

    def _link_after(self, anchor, item):
        item.next = anchor.next
        item.prev = anchor.next.prev
        anchor.next.prev = item
        anchor.next = item

    def _insert(self, item, before, anchor):
        item.unlink()
        if anchor is None:
            anchor = self.head
        if before:
            self._link_after(anchor.prev, item)
        else:
            self._link_after(anchor, item)

    def _create(self, value, weight, before):
        item = _Item(value, weight)
        item.prev = item.next = None
        self._insert(item, before, None)
        return item

    def _find_higher_or_equal(self, item, weight):
        while item is not None and item is not self.head:
            if item.weight >= weight:
                return item
            item = item.prev
        return self.head

    def _fixup_by_weight(self, item, max_weight):
        if item.weight < max_weight:
            higher = self._find_higher_or_equal(self.head.prev, item.weight)
            item.unlink()
            self._link_after(higher, item)
        else:
            max_weight = item.weight
        return max_weight

    # -- tree construction, mirroring BuildTree ----------------------------

    def build(self, data_type):
        data_type &= 0x0F
        if data_type >= MAX_DATA_TYPE:
            raise HuffmanError("data type %d has no weight table" % data_type)

        weights = DATA_DISTRIBUTIONS[data_type]
        self.by_byte = {}
        max_weight = 0

        for value in range(0x100):
            if weights[value] != 0:
                item = self._create(value, weights[value], before=False)
                self.by_byte[value] = item
                max_weight = self._fixup_by_weight(item, max_weight)

        self.by_byte[END_OF_STREAM] = self._create(END_OF_STREAM, 1, before=True)
        self.by_byte[NEW_BYTE_ESCAPE] = self._create(NEW_BYTE_ESCAPE, 1, before=True)

        # Combine from the light end upwards, as BuildTree does.
        child_lo = self.head.prev
        while child_lo is not self.head:
            child_hi = child_lo.prev
            if child_hi is self.head:
                break
            parent = self._create(0, child_hi.weight + child_lo.weight, before=False)
            child_lo.parent = parent
            child_hi.parent = parent
            parent.child_lo = child_lo
            max_weight = self._fixup_by_weight(parent, max_weight)
            child_lo = child_hi.prev

    # -- the adaptive part, mirroring IncWeightsAndRebalance ---------------

    def _inc_weights_and_rebalance(self, item):
        while item is not None:
            item.weight += 1
            higher = self._find_higher_or_equal(item.prev, item.weight)
            child_hi = higher.next

            if child_hi is not item:
                child_hi.unlink()
                self._link_after(item, child_hi)
                item.unlink()
                self._link_after(higher, item)

                child_lo = child_hi.parent.child_lo
                parent = item.parent
                if parent.child_lo is item:
                    parent.child_lo = child_hi
                if child_lo is child_hi:
                    child_hi.parent.child_lo = item
                parent = item.parent
                item.parent = child_hi.parent
                child_hi.parent = parent

            item = item.parent

    def _insert_branch_and_rebalance(self, value1, value2):
        last = self.head.prev

        child_hi = self._create(value1, last.weight, before=True)
        child_hi.parent = last
        self.by_byte[value1] = child_hi

        child_lo = self._create(value2, 0, before=True)
        child_lo.parent = last
        last.child_lo = child_lo
        self.by_byte[value2] = child_lo

        self._inc_weights_and_rebalance(child_lo)

    # -- decoding, mirroring DecodeOneByte / Decompress --------------------

    def _decode_one(self, reader):
        if self.head.next is self.head:
            raise HuffmanError("empty tree")

        item = self.head.next
        while item.child_lo is not None:
            bit = reader.bit()
            if bit is None:
                raise HuffmanError("stream ended mid-symbol")
            # A set bit takes the higher-weight child, which is the list
            # neighbour to the left of child_lo.
            item = item.child_lo.prev if bit else item.child_lo
        return item.value

    def decompress(self, data, expected_size):
        if expected_size == 0:
            return b""

        reader = _BitReader(data)
        data_type = reader.byte()
        if data_type is None:
            raise HuffmanError("stream too short for a data type")
        self.sparse = data_type == DATA_TYPE_SPARSE
        self.build(data_type)

        out = bytearray()
        while True:
            value = self._decode_one(reader)
            if value == END_OF_STREAM:
                break

            if value == NEW_BYTE_ESCAPE:
                value = reader.byte()
                if value is None:
                    raise HuffmanError("escape with no byte after it")
                self._insert_branch_and_rebalance(self.head.prev.value, value)
                if not self.sparse:
                    self._inc_weights_and_rebalance(self.by_byte[value])

            if len(out) >= expected_size:
                break
            out.append(value)

            if self.sparse:
                self._inc_weights_and_rebalance(self.by_byte[value])

        return bytes(out)


def huffman_decompress(data, expected_size):
    """Decompresses one Huffman-compressed sector."""
    return HuffmanTree().decompress(data, expected_size)


# ---------------------------------------------------------------- ADPCM

INITIAL_STEP_INDEX = 0x2C

NEXT_STEP_TABLE = (
    -1, 0, -1, 4, -1, 2, -1, 6,
    -1, 1, -1, 5, -1, 3, -1, 7,
    -1, 1, -1, 5, -1, 3, -1, 7,
    -1, 2, -1, 4, -1, 6, -1, 8,
)

STEP_SIZE_TABLE = (
    7, 8, 9, 10, 11, 12, 13, 14,
    16, 17, 19, 21, 23, 25, 28, 31,
    34, 37, 41, 45, 50, 55, 60, 66,
    73, 80, 88, 97, 107, 118, 130, 143,
    157, 173, 190, 209, 230, 253, 279, 307,
    337, 371, 408, 449, 494, 544, 598, 658,
    724, 796, 876, 963, 1060, 1166, 1282, 1411,
    1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
    3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
    7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
    15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
    32767,
)


def _next_step_index(step_index, encoded):
    step_index += NEXT_STEP_TABLE[encoded & 0x1F]
    if step_index < 0:
        return 0
    if step_index > 88:
        return 88
    return step_index


def _update_predicted(predicted, encoded, difference, bit_mask=0x40):
    if encoded & bit_mask:
        predicted -= difference
        if predicted <= -32768:
            predicted = -32768
    else:
        predicted += difference
        if predicted >= 32767:
            predicted = 32767
    return predicted


def _decode_sample(predicted, encoded, step_size, difference):
    for shift in range(6):
        if encoded & (1 << shift):
            difference += step_size >> shift
    return _update_predicted(predicted, encoded, difference)


def _to_signed16(value):
    value &= 0xFFFF
    return value - 0x10000 if value & 0x8000 else value


def adpcm_decompress(data, channels, expected_size):
    """Decompresses one ADPCM-compressed sector.

    Mirrors ``DecompressADPCM``. The output is capped at ``expected_size``, the
    way the C is bounded by its output buffer: it stops writing when full and
    reports how much it wrote.
    """
    predicted = [0, 0]
    step_indexes = [INITIAL_STEP_INDEX, INITIAL_STEP_INDEX]

    out = bytearray()
    at = 0

    def read_byte():
        nonlocal at
        if at >= len(data):
            return None
        value = data[at]
        at += 1
        return value

    def read_word():
        nonlocal at
        if len(data) - at < 2:
            return None
        value = data[at] | (data[at + 1] << 8)
        at += 2
        return _to_signed16(value)

    def write_word(sample):
        if len(out) + 2 > expected_size:
            return False
        out.append(sample & 0xFF)
        out.append((sample >> 8) & 0xFF)
        return True

    # The first byte is always zero; the second holds the bit shift.
    read_byte()
    bit_shift = read_byte()
    if bit_shift is None:
        return bytes(out)

    for channel in range(channels):
        initial = read_word()
        if initial is None:
            return bytes(out)
        predicted[channel] = initial
        if not write_word(initial):
            return bytes(out)

    channel_index = channels - 1

    while True:
        encoded = read_byte()
        if encoded is None:
            break

        channel_index = (channel_index + 1) % channels

        if encoded == 0x80:
            if step_indexes[channel_index] != 0:
                step_indexes[channel_index] -= 1
            if not write_word(predicted[channel_index]):
                return bytes(out)
        elif encoded == 0x81:
            step_indexes[channel_index] += 8
            if step_indexes[channel_index] > 0x58:
                step_indexes[channel_index] = 0x58
            # Stay on the same channel for the next pass.
            channel_index = (channel_index + 1) % channels
        else:
            step_index = step_indexes[channel_index]
            step_size = STEP_SIZE_TABLE[step_index]
            predicted[channel_index] = _to_signed16(_decode_sample(
                predicted[channel_index], encoded, step_size, step_size >> bit_shift))
            if not write_word(predicted[channel_index]):
                break
            step_indexes[channel_index] = _next_step_index(step_index, encoded)

    return bytes(out)
