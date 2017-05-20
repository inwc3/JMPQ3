/*
Copyright 2014 Google Inc. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Author: eustas.ru@gmail.com (Eugene Klyuchnikov)
*/

package ru.eustas.zopfli;

class Katajainen {

    static void lengthLimitedCodeLengths(Cookie cookie, int[] frequencies, int maxBits,
                                         int[] bitLengths) {
        cookie.resetPool();
        int n = frequencies.length;
        int nn = 0;
        Cookie.Node[] leaves = cookie.leaves1;
        for (int i = 0; i < n; i++) {
            if (frequencies[i] != 0) {
                leaves[nn] = cookie.node(frequencies[i], i, null);
                nn++;
            }
        }

        if (nn == 0) {
            return;
        }
        if (nn == 1) {
            bitLengths[leaves[0].count] = 1;
            return;
        }

        Cookie.Node[] leaves2 = cookie.leaves2;
        System.arraycopy(leaves, 0, leaves2, 0, nn);
        sort(leaves2, leaves, 0, nn);

        Cookie.Node[] list0 = cookie.list0;
        Cookie.Node node0 = cookie.node(leaves[0].weight, 1, null);

        Cookie.Node[] list1 = cookie.list1;
        Cookie.Node node1 = cookie.node(leaves[1].weight, 2, null);

        for (int i = 0; i < maxBits; ++i) {
            list0[i] = node0;
            list1[i] = node1;
        }

        int numBoundaryPmRuns = 2 * nn - 4;
        for (int i = 0; i < numBoundaryPmRuns; i++) {
            boolean last = i == numBoundaryPmRuns - 1;
            boundaryPm(cookie, leaves, list0, list1, nn, maxBits - 1, last);
        }

        for (Cookie.Node node = list1[maxBits - 1]; node != null; node = node.tail) {
            for (int i = node.count - 1; i >= 0; --i) {
                bitLengths[leaves[i].count]++;
            }
        }
    }

    private static void boundaryPm(Cookie cookie, Cookie.Node[] leaves, Cookie.Node[] list0, Cookie.Node[] list1, int numSymbols, int index,
                                   boolean last) {
        int lastCount = list1[index].count;

        if (index == 0 && lastCount >= numSymbols) {
            return;
        }

        list0[index] = list1[index];

        if (index == 0) {
            list1[index] = cookie.node(leaves[lastCount].weight, lastCount + 1, null);
        } else {
            int sum = list0[index - 1].weight + list1[index - 1].weight;
            if (lastCount < numSymbols && sum > leaves[lastCount].weight) {
                list1[index] = cookie.node(leaves[lastCount].weight, lastCount + 1, list1[index].tail);
            } else {
                list1[index] = cookie.node(sum, lastCount, list1[index - 1]);
                if (!last) {
                    boundaryPm(cookie, leaves, list0, list1, numSymbols, index - 1, false);
                    boundaryPm(cookie, leaves, list0, list1, numSymbols, index - 1, false);
                }
            }
        }
    }

    private static void sort(Cookie.Node[] src, Cookie.Node[] dest, int low, int high) {
        int length = high - low;

        if (length < 7) {
            for (int i = low + 1; i < high; i++)
                for (int j = i, k = i - 1; j > low && (dest[k].weight > dest[j].weight); --j, --k) {
                    Cookie.Node t = dest[j];
                    dest[j] = dest[k];
                    dest[k] = t;
                }
            return;
        }

        int mid = (low + high) >>> 1;
        sort(dest, src, low, mid);
        sort(dest, src, mid, high);

        for (int i = low, p = low, q = mid; i < high; i++) {
            if (q >= high || p < mid && (src[p].weight <= src[q].weight))
                dest[i] = src[p++];
            else
                dest[i] = src[q++];
        }
    }
}
