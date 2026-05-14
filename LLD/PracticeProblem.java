import java.util.Arrays;
import java.util.PriorityQueue;

public class PracticeProblem {

    public static void main(String[] args) {
        // You can write your own test cases here to test the code you implemented for
        // the practice problems.
        int[][] intervals1 = { { 4, 9 } };
        int[][] intervals2 = { { 1, 10 }, { 2, 7 }, { 3, 19 }, { 8, 12 }, { 10, 20 } };
        System.out.println(getMeetingRooms(intervals2));
    }

    public static int getMeetingRooms(int[][] intervals) {
        Arrays.sort(intervals, (a, b) -> a[0] - b[0]);
        PriorityQueue<Integer> pq = new PriorityQueue<>();
        for (int[] interval : intervals) {
            if (!pq.isEmpty() && pq.peek() <= interval[0]) {
                pq.poll();
            }
            pq.offer(interval[1]);
        }
        return pq.size();

    }
}

// Given an array of meeting time interval objects consisting of start and end
// times [[start_1,end_1],[start_2,end_2],...] (start_i < end_i), find the
// minimum number of rooms required to schedule all meetings without any
// conflicts.
// Note: (0,8),(8,10) is NOT considered a conflict at 8.
// Example 1:
// Input: intervals = [(4,9)]
// Output: 1
//
// Example 2:
// Input: intervals = [(0,40),(5,10),(15,20)]
// Output: 2
// [(1,10), (2,7), (3,19), (8,12), (10,20)]