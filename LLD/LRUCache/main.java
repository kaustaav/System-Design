package LRUCache;

import java.util.HashMap;
import java.util.Map;

public class main {
}

class Node<K, V> {
    private final K key;
    private V value;
    Node<K, V> prev;
    Node<K, V> next;

    public Node(K key, V value) {
        this.key = key;
        this.value = value;
    }

    public K getKey() {
        return key;
    }

    public V getValue() {
        return value;
    }

    public void setValue(V value) {
        this.value = value;
    }
}

class DoublyLinkedList<K, V> {
    private Node<K, V> head;
    private Node<K, V> tail;

    public DoublyLinkedList() {
        head = new Node<K, V>(null, null);
        tail = new Node<K, V>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public void addFirst(Node<K, V> node) {
        node.next = head.next;
        node.next.prev = node;
        head.next = node;
        node.prev = head;
    }

    public void remove(Node<K, V> node) {
        node.next.prev = node.prev;
        node.prev.next = node.next;
    }

    public void moveToFront(Node<K, V> node) {
        remove(node);
        addFirst(node);
    }

    public Node<K, V> removeLast() {
        Node<K, V> lastNode = tail.prev;
        if(lastNode == head)
            return null;
        remove(lastNode);
        return lastNode;
    }
}

class LRUCache<K, V> {
    private final Map<K, Node<K, V>> map;
    private final DoublyLinkedList<K, V> doublyLinkedList;
    private final int size;

    public LRUCache(int size) {
        this.map = new HashMap<>();
        this.doublyLinkedList = new DoublyLinkedList<K, V>();
        this.size = size;
    }

    public synchronized void put(K key, V value) {
        if(map.containsKey(key)) {
            Node<K, V> node = map.get(key);
            node.setValue(value);
            doublyLinkedList.moveToFront(node);
        }
        else {
            if(size == map.size()) {
                Node<K, V> lastNode = doublyLinkedList.removeLast();
                map.remove(lastNode.getKey());
            }
            Node<K, V> node = new Node<>(key, value);
            map.put(key, node);
            doublyLinkedList.addFirst(node);
        }
    }

    public synchronized V get(K key) {
        Node<K, V> node = map.getOrDefault(key, null);
        if(node == null)
            return null;
        doublyLinkedList.moveToFront(node);
        return node.getValue();
    }
}
