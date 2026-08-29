package com.chua.example.tree;

import com.chua.common.support.tree.BTree;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class DebugBTree {
    public static void main(String[] args) {
        BTree<Integer, String> tree = new BTree<>(200);
        int total = 1_000_000;
        for (int i = 0; i < total; i++) {
            tree.put(i, "v" + i);
        }
        log.info("size={}", tree.size());

        List<Map.Entry<Integer, String>> all = tree.range(0, total);
        log.info("range[0,1M) size={}", all.size());

        // Check for missing keys
        int missing = 0;
        for (int i = 0; i < total; i++) {
            if (tree.get(i).isEmpty()) {
                missing++;
                if (missing <= 10) {
                    log.info("missing key: {}", i);
                }
            }
        }
        log.info("total missing: {}", missing);
    }
}
