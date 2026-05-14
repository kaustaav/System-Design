package Facebook;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class main {
}

class User {
    private final String userId;
    private String userName;

    public User(String userId, String userName) {
        this.userId = userId;
        this.userName = userName;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }
}

class Post {
    private final String postId;
    private final String content;
    private final User author;
    private final LocalDateTime creationTime;
    private final Set<String> likes;
    private final Queue<Comment> comments;

    public Post(String postId, String content, User author) {
        this.postId = postId;
        this.content = content;
        this.author = author;
        this.creationTime = LocalDateTime.now();
        this.likes = ConcurrentHashMap.newKeySet();
        this.comments = new ConcurrentLinkedQueue<>();
    }

    public String getPostId() {
        return postId;
    }

    public User getAuthor() {
        return author;
    }

    public LocalDateTime getCreationTime() {
        return creationTime;
    }

    public Integer getLikesCount() {
        return likes.size();
    }

    public void addLike(String userId) {
        likes.add(userId);
    }

    public void addComment(User user, Comment comment) {
        comments.add(comment);
    }
    // TODO: Getters
}

class Comment {
    private final String commentId;
    private final String postId;
    private final User commentedBy;
    private final String content;
    private final LocalDateTime timestamp;

    public Comment(String commentId, String postId, User commentedBy, String content) {
        this.commentId = commentId;
        this.postId = postId;
        this.commentedBy = commentedBy;
        this.content = content;
        this.timestamp = LocalDateTime.now();
    }

    // TODO: Getters
}

class UserRepo {
    private static volatile UserRepo instance;
    private final Map<String, User> userMap;

    private UserRepo() {
        this.userMap = new ConcurrentHashMap<>();
    }

    public static UserRepo getInstance() {
        if (instance == null) {
            synchronized (UserRepo.class) {
                if (instance == null)
                    instance = new UserRepo();
            }
        }
        return instance;
    }

    public Map<String, User> getUsers() {
        return userMap;
    }

    public User getUserById(String userId) {
        if (!userMap.containsKey(userId))
            throw new IllegalStateException("User not found");
        return userMap.get(userId);
    }

    public void addUser(User user) {
        if (userMap.containsKey(user.getUserId()))
            throw new IllegalStateException("User already exists");
        userMap.put(user.getUserId(), user);
    }
}

class PostRepo {
    private static volatile PostRepo instance;
    private final Map<String, Set<Post>> postsMap;

    private PostRepo() {
        this.postsMap = new ConcurrentHashMap<>();
    }

    public static PostRepo getInstance() {
        if (instance == null)
            synchronized (PostRepo.class) {
                if (instance == null)
                    instance = new PostRepo();
            }
        return instance;
    }

    public void createPost(Post post) {
        postsMap.computeIfAbsent(post.getAuthor().getUserId(), k -> ConcurrentHashMap.newKeySet()).add(post);
    }

    private List<Post> getPostsByUser(String userId) {
        if (UserRepo.getInstance().getUserById(userId) == null)
            throw new IllegalStateException("User not found");
        return postsMap.getOrDefault(userId, new HashSet<>()).stream().toList();
    }

    public List<Post> getPostsByUser(String userId, Integer threshold) {
        List<Post> posts = new ArrayList<>(getPostsByUser(userId));
        posts.sort((a, b) -> a.getCreationTime().compareTo(b.getCreationTime()));
        return posts.subList(0, Math.min(posts.size(), threshold));
    }
}

class RelationshipRepo {
    private static volatile RelationshipRepo instance;
    private final Map<String, Set<String>> followersMap;
    private final Map<String, Set<String>> followeesMap;

    private RelationshipRepo() {
        this.followersMap = new ConcurrentHashMap<>();
        this.followeesMap = new ConcurrentHashMap<>();
    }

    public static RelationshipRepo getInstance() {
        if (instance == null)
            synchronized (RelationshipRepo.class) {
                if (instance == null)
                    instance = new RelationshipRepo();
            }
        return instance;
    }

    public List<String> getFollowersOfUser(String userId) {
        if (!UserRepo.getInstance().getUsers().containsKey(userId))
            throw new IllegalStateException("User not found");
        return followersMap.getOrDefault(userId, new HashSet<>()).stream().toList();
    }

    public List<String> getFolloweesOfUser(String userId) {
        if (!UserRepo.getInstance().getUsers().containsKey(userId))
            throw new IllegalStateException("User not found");
        return followeesMap.getOrDefault(userId, new HashSet<>()).stream().toList();
    }

    public void followUser(String follower, String followee) {
        followersMap.computeIfAbsent(followee, k -> ConcurrentHashMap.newKeySet())
                .add(follower);
        followeesMap.computeIfAbsent(follower, k -> ConcurrentHashMap.newKeySet())
                .add(followee);
    }
}

interface FeedGenerationStrategy {
    public List<Post> getFeedForUser(String userId);
}

class ChronologicalFeedGenerationStrategy implements FeedGenerationStrategy {
    private final UserService userService;
    private final PostService postService;

    public ChronologicalFeedGenerationStrategy(UserService userService, PostService postService) {
        this.userService = userService;
        this.postService = postService;
    }

    @Override
    public List<Post> getFeedForUser(String userId) {
        User user = userService.getUserById(userId);
        if (user == null)
            throw new IllegalStateException("User not found");
        List<String> followees = RelationshipRepo.getInstance().getFolloweesOfUser(userId);
        List<Post> posts = new ArrayList<>();
        for (String followee : followees) {
            posts.addAll(postService.getPostsByUser(followee, 100));
        }
        posts.sort((a, b) -> b.getCreationTime().compareTo(a.getCreationTime()));
        return posts;
    }
}

class NewsFeedService {
    private FeedGenerationStrategy feedGenerationStrategy;

    public NewsFeedService(FeedGenerationStrategy feedGenerationStrategy) {
        this.feedGenerationStrategy = feedGenerationStrategy;
    }

    public void setFeedGenerationStrategy(FeedGenerationStrategy feedGenerationStrategy) {
        this.feedGenerationStrategy = feedGenerationStrategy;
    }

    public List<Post> getFeedForUser(User user) {
        return feedGenerationStrategy.getFeedForUser(user.getUserId());
    }
}

class UserService {
    private final UserRepo userRepo;

    public UserService() {
        this.userRepo = UserRepo.getInstance();
    }

    public void createUser(User user) {
        userRepo.addUser(user);
    }

    public User getUserById(String userId) {
        return userRepo.getUserById(userId);
    }
}

class PostService {
    private final PostRepo postRepo;
    private final UserService userService;

    public PostService(UserService userService) {
        this.postRepo = PostRepo.getInstance();
        this.userService = userService;
    }

    public void createPost(Post post) {
        postRepo.createPost(post);
    }

    public List<Post> getPostsByUser(String userId, Integer threshold) {
        return postRepo.getPostsByUser(userId, threshold);
    }

    public void addLike(Post post, String userId) {
        if (userService.getUserById(userId) == null)
            throw new IllegalStateException("Author not found");
        post.addLike(userId);
    }

    public void addComment(Post post, User user, Comment comment) {
        if (userService.getUserById(user.getUserId()) == null)
            throw new IllegalStateException("Author not found");
        post.addComment(user, comment);
    }
}

class RelationshipService {
    private final RelationshipRepo relationshipRepo;
    private final UserService userService;

    public RelationshipService(UserService userService) {
        this.relationshipRepo = RelationshipRepo.getInstance();
        this.userService = userService;
    }

    public List<String> getFollowersOfUser(String userId) {
        return relationshipRepo.getFollowersOfUser(userId);
    }

    public List<String> getFolloweesOfUser(String userId) {
        return relationshipRepo.getFolloweesOfUser(userId);
    }

    public void followUser(String follower, String followee) {
        if (userService.getUserById(follower) == null)
            throw new IllegalStateException("Follower not found");

        if (userService.getUserById(followee) == null)
            throw new IllegalStateException("Followee not found");
        relationshipRepo.followUser(follower, followee);
    }
}

class Facebook {
    private static volatile Facebook instance;
    private final UserService userService;
    private final PostService postService;
    private final RelationshipService relationshipService;
    private final NewsFeedService newsFeedService;

    private Facebook() {
        this.userService = new UserService();
        this.postService = new PostService(userService);
        this.relationshipService = new RelationshipService(userService);
        this.newsFeedService = new NewsFeedService(new ChronologicalFeedGenerationStrategy(userService, postService));
    }

    public static Facebook getInstance() {
        if (instance == null)
            synchronized (Facebook.class) {
                if (instance == null)
                    instance = new Facebook();
            }
        return instance;
    }

    public void createUser(String userId, String userName) {
        User user = new User(userId, userName);
        userService.createUser(user);
    }

    public void createPost(String postId, String content, User author) {
        if (userService.getUserById(author.getUserId()) == null)
            throw new IllegalStateException("Author not found");
        Post post = new Post(postId, content, author);
        postService.createPost(post);
    }

    public void followUser(String follower, String followee) {
        relationshipService.followUser(follower, followee);
    }

    public void addLike(User user, Post post) {
        postService.addLike(post, user.getUserId());
    }

    public void addComment(User user, Post post, Comment comment) {
        postService.addComment(post, user, comment);
    }

    public List<Post> getFeedForUser(User user) {
        return newsFeedService.getFeedForUser(user);
    }

    public List<Post> getPostForUser(User user) {
        return postService.getPostsByUser(user.getUserId(), 100);
    }
}
