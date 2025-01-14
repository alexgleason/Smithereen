package smithereen.storage;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import smithereen.Config;
import smithereen.data.ListAndTotal;
import smithereen.data.UriBuilder;
import smithereen.data.feed.AddFriendNewsfeedEntry;
import smithereen.data.feed.JoinGroupNewsfeedEntry;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.Utils;
import smithereen.data.ForeignGroup;
import smithereen.data.ForeignUser;
import smithereen.data.Group;
import smithereen.data.User;
import smithereen.data.UserInteractions;
import smithereen.data.feed.NewsfeedEntry;
import smithereen.data.Post;
import smithereen.data.feed.PostNewsfeedEntry;
import smithereen.data.feed.RetootNewsfeedEntry;
import spark.utils.StringUtils;

public class PostStorage{
	public static int createWallPost(int userID, int ownerUserID, int ownerGroupID, String text, int[] replyKey, List<User> mentionedUsers, String attachments, String contentWarning) throws SQLException{
		Connection conn=DatabaseConnectionManager.getConnection();
		PreparedStatement stmt=conn.prepareStatement("INSERT INTO wall_posts (author_id, owner_user_id, owner_group_id, `text`, reply_key, mentions, attachments, content_warning) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
		stmt.setInt(1, userID);
		if(ownerUserID>0){
			stmt.setInt(2, ownerUserID);
			stmt.setNull(3, Types.INTEGER);
		}else if(ownerGroupID>0){
			stmt.setNull(2, Types.INTEGER);
			stmt.setInt(3, ownerGroupID);
		}else{
			throw new IllegalArgumentException("Need either ownerUserID or ownerGroupID");
		}
		stmt.setString(4, text);
		stmt.setBytes(5, Utils.serializeIntArray(replyKey));
		byte[] mentions=null;
		if(!mentionedUsers.isEmpty()){
			mentions=Utils.serializeIntArray(mentionedUsers.stream().mapToInt(u->u.id).toArray());
		}
		stmt.setBytes(6, mentions);
		stmt.setString(7, attachments);
		stmt.setString(8, contentWarning);
		stmt.execute();
		try(ResultSet keys=stmt.getGeneratedKeys()){
			keys.first();
			int id=keys.getInt(1);
			if(userID==ownerUserID && replyKey==null){
				stmt=conn.prepareStatement("INSERT INTO `newsfeed` (`type`, `author_id`, `object_id`) VALUES (?, ?, ?)");
				stmt.setInt(1, NewsfeedEntry.Type.POST.ordinal());
				stmt.setInt(2, userID);
				stmt.setInt(3, id);
				stmt.execute();
			}
			if(replyKey!=null && replyKey.length>0){
				conn.createStatement().execute("UPDATE wall_posts SET reply_count=reply_count+1 WHERE id IN ("+Arrays.stream(replyKey).mapToObj(String::valueOf).collect(Collectors.joining(","))+")");
			}
			return id;
		}
	}

	public static void putForeignWallPost(Post post) throws SQLException{
		Post existing=getPostByID(post.activityPubID);
		Connection conn=DatabaseConnectionManager.getConnection();

		PreparedStatement stmt;
		if(existing==null){
			stmt=new SQLQueryBuilder(conn)
					.insertInto("wall_posts")
					.value("author_id", post.user.id)
					.value("owner_user_id", post.owner instanceof User ? post.owner.getLocalID() : null)
					.value("owner_group_id", post.owner instanceof Group ? post.owner.getLocalID() : null)
					.value("text", post.content)
					.value("attachments", post.serializeAttachments())
					.value("content_warning", post.hasContentWarning() ? post.summary : null)
					.value("ap_url", post.url.toString())
					.value("ap_id", post.activityPubID.toString())
					.value("reply_key", Utils.serializeIntArray(post.replyKey))
					.value("created_at", new Timestamp(post.published.getTime()))
					.value("mentions", post.mentionedUsers.isEmpty() ? null : Utils.serializeIntArray(post.mentionedUsers.stream().mapToInt(u->u.id).toArray()))
					.value("ap_replies", Objects.toString(post.getRepliesURL(), null))
					.createStatement(Statement.RETURN_GENERATED_KEYS);
		}else{
			stmt=new SQLQueryBuilder(conn)
					.update("wall_posts")
					.where("ap_id=?", post.activityPubID.toString())
					.value("text", post.content)
					.value("attachments", post.serializeAttachments())
					.value("content_warning", post.hasContentWarning() ? post.summary : null)
					.value("mentions", post.mentionedUsers.isEmpty() ? null : Utils.serializeIntArray(post.mentionedUsers.stream().mapToInt(u->u.id).toArray()))
					.createStatement();
		}
		stmt.execute();
		if(existing==null){
			try(ResultSet res=stmt.getGeneratedKeys()){
				res.first();
				post.id=res.getInt(1);
			}
			if(post.owner.equals(post.user) && post.getReplyLevel()==0){
				new SQLQueryBuilder(conn)
						.insertInto("newsfeed")
						.value("type", NewsfeedEntry.Type.POST)
						.value("author_id", post.user.id)
						.value("object_id", post.id)
						.value("time", new Timestamp(post.published.getTime()))
						.createStatement()
						.execute();
			}
			if(post.getReplyLevel()>0){
				new SQLQueryBuilder(conn)
						.update("wall_posts")
						.valueExpr("reply_count", "reply_count+1")
						.whereIn("id", Arrays.stream(post.replyKey).boxed().collect(Collectors.toList()))
						.createStatement()
						.execute();
			}
		}else{
			post.id=existing.id;
		}
	}

	public static List<NewsfeedEntry> getFeed(int userID, int startFromID, int offset, int[] total) throws SQLException{
		Connection conn=DatabaseConnectionManager.getConnection();
		PreparedStatement stmt;
		if(total!=null){
			stmt=conn.prepareStatement("SELECT COUNT(*) FROM `newsfeed` WHERE `author_id` IN (SELECT followee_id FROM followings WHERE follower_id=? UNION SELECT ?) AND `id`<=?");
			stmt.setInt(1, userID);
			stmt.setInt(2, userID);
			stmt.setInt(3, startFromID==0 ? Integer.MAX_VALUE : startFromID);
			try(ResultSet res=stmt.executeQuery()){
				res.first();
				total[0]=res.getInt(1);
			}
		}
		stmt=conn.prepareStatement("SELECT `type`, `object_id`, `author_id`, `id`, `time` FROM `newsfeed` WHERE (`author_id` IN (SELECT followee_id FROM followings WHERE follower_id=?) OR (type=0 AND author_id=?)) AND `id`<=? ORDER BY `time` DESC LIMIT ?,25");
		stmt.setInt(1, userID);
		stmt.setInt(2, userID);
		stmt.setInt(3, startFromID==0 ? Integer.MAX_VALUE : startFromID);
		stmt.setInt(4, offset);
		ArrayList<NewsfeedEntry> posts=new ArrayList<>();
		ArrayList<Integer> needPosts=new ArrayList<>();
		HashMap<Integer, Post> postMap=new HashMap<>();
		try(ResultSet res=stmt.executeQuery()){
			if(res.first()){
				do{
					NewsfeedEntry.Type type=NewsfeedEntry.Type.values()[res.getInt(1)];
					NewsfeedEntry entry=switch(type){
						case POST -> {
							PostNewsfeedEntry _entry=new PostNewsfeedEntry();
							_entry.objectID=res.getInt(2);
							needPosts.add(_entry.objectID);
							yield _entry;
						}
						case RETOOT -> {
							RetootNewsfeedEntry _entry=new RetootNewsfeedEntry();
							_entry.objectID=res.getInt(2);
							_entry.author=UserStorage.getById(res.getInt(3));
							needPosts.add(_entry.objectID);
							yield _entry;
						}
						case ADD_FRIEND -> {
							AddFriendNewsfeedEntry _entry=new AddFriendNewsfeedEntry();
							_entry.objectID=res.getInt(2);
							_entry.friend=UserStorage.getById(_entry.objectID);
							_entry.author=UserStorage.getById(res.getInt(3));
							yield _entry;
						}
						case JOIN_GROUP -> {
							JoinGroupNewsfeedEntry _entry=new JoinGroupNewsfeedEntry();
							_entry.objectID=res.getInt(2);
							_entry.group=GroupStorage.getById(_entry.objectID);
							_entry.author=UserStorage.getById(res.getInt(3));
							yield _entry;
						}
					};
					entry.type=type;
					entry.id=res.getInt(4);
					entry.time=res.getTimestamp(5).toInstant();
					posts.add(entry);
				}while(res.next());
			}
		}
		if(!needPosts.isEmpty()){
			StringBuilder sb=new StringBuilder();
			sb.append("SELECT * FROM `wall_posts` WHERE `id` IN (");
			boolean first=true;
			for(int id:needPosts){
				if(!first){
					sb.append(',');
				}else{
					first=false;
				}
				sb.append(id);
			}
			sb.append(')');
			try(ResultSet res=conn.createStatement().executeQuery(sb.toString())){
				if(res.first()){
					do{
						Post post=Post.fromResultSet(res);
						postMap.put(post.id, post);
					}while(res.next());
				}
			}
			for(NewsfeedEntry e:posts){
				if(e instanceof PostNewsfeedEntry){
					Post post=postMap.get(e.objectID);
					if(post!=null)
						((PostNewsfeedEntry) e).post=post;
				}
			}
		}
		return posts;
	}

	public static List<Post> getWallPosts(int ownerID, boolean isGroup, int minID, int maxID, int offset, int[] total, boolean ownOnly) throws SQLException{
		Connection conn=DatabaseConnectionManager.getConnection();
		PreparedStatement stmt;
		String ownCondition=ownOnly ? " AND owner_user_id=author_id" : "";
		String ownerField=isGroup ? "owner_group_id" : "owner_user_id";
		if(total!=null){
			stmt=conn.prepareStatement("SELECT COUNT(*) FROM `wall_posts` WHERE `"+ownerField+"`=? AND `reply_key` IS NULL"+ownCondition);
			stmt.setInt(1, ownerID);
			try(ResultSet res=stmt.executeQuery()){
				res.first();
				total[0]=res.getInt(1);
			}
		}
		if(minID>0){
			stmt=conn.prepareStatement("SELECT * FROM `wall_posts` WHERE `"+ownerField+"`=? AND `id`>? AND `reply_key` IS NULL"+ownCondition+" ORDER BY created_at DESC LIMIT 25");
			stmt.setInt(2, minID);
		}else if(maxID>0){
			stmt=conn.prepareStatement("SELECT * FROM `wall_posts` WHERE `"+ownerField+"`=? AND `id`=<? AND `reply_key` IS NULL"+ownCondition+" ORDER BY created_at DESC LIMIT "+offset+",25");
			stmt.setInt(2, maxID);
		}else{
			stmt=conn.prepareStatement("SELECT * FROM `wall_posts` WHERE `"+ownerField+"`=? AND `reply_key` IS NULL"+ownCondition+" ORDER BY created_at DESC LIMIT "+offset+",25");
		}
		stmt.setInt(1, ownerID);
		ArrayList<Post> posts=new ArrayList<>();
		try(ResultSet res=stmt.executeQuery()){
			if(res.first()){
				do{
					posts.add(Post.fromResultSet(res));
				}while(res.next());
			}
		}
		return posts;
	}

	public static List<URI> getWallPostActivityPubIDs(int ownerID, boolean isGroup, int offset, int count, int[] total) throws SQLException{
		String ownerField=isGroup ? "owner_group_id" : "owner_user_id";
		Connection conn=DatabaseConnectionManager.getConnection();
		PreparedStatement stmt=new SQLQueryBuilder(conn)
				.selectFrom("wall_posts")
				.count()
				.where(ownerField+"=? AND reply_key IS NULL", ownerID)
				.createStatement();
		try(ResultSet res=stmt.executeQuery()){
			res.first();
			total[0]=res.getInt(1);
		}
		stmt=new SQLQueryBuilder(conn)
				.selectFrom("wall_posts")
				.columns("id", "ap_id")
				.where(ownerField+"=? AND reply_key IS NULL", ownerID)
				.orderBy("id ASC")
				.limit(count, offset)
				.createStatement();
		try(ResultSet res=stmt.executeQuery()){
			res.beforeFirst();
			List<URI> ids=new ArrayList<>();
			while(res.next()){
				String apID=res.getString(2);
				if(StringUtils.isNotEmpty(apID)){
					ids.add(URI.create(apID));
				}else{
					ids.add(UriBuilder.local().path("posts", res.getInt(1)+"").build());
				}
			}
			return ids;
		}
	}

	public static List<Post> getWallToWall(int userID, int otherUserID, int offset, int[] total) throws SQLException{
		Connection conn=DatabaseConnectionManager.getConnection();
		PreparedStatement stmt;
		if(total!=null){
			stmt=conn.prepareStatement("SELECT COUNT(*) FROM wall_posts WHERE ((owner_user_id=? AND author_id=?) OR (owner_user_id=? AND author_id=?)) AND `reply_key` IS NULL");
			stmt.setInt(1, userID);
			stmt.setInt(2, otherUserID);
			stmt.setInt(3, otherUserID);
			stmt.setInt(4, userID);
			try(ResultSet res=stmt.executeQuery()){
				res.first();
				total[0]=res.getInt(1);
			}
		}
		stmt=conn.prepareStatement("SELECT * FROM wall_posts WHERE ((owner_user_id=? AND author_id=?) OR (owner_user_id=? AND author_id=?)) AND `reply_key` IS NULL ORDER BY created_at DESC LIMIT "+offset+",25");
		stmt.setInt(1, userID);
		stmt.setInt(2, otherUserID);
		stmt.setInt(3, otherUserID);
		stmt.setInt(4, userID);
		ArrayList<Post> posts=new ArrayList<>();
		try(ResultSet res=stmt.executeQuery()){
			if(res.first()){
				do{
					posts.add(Post.fromResultSet(res));
				}while(res.next());
			}
		}
		return posts;
	}

	public static @NotNull Post getPostOrThrow(int postID, boolean onlyLocal) throws SQLException{
		if(postID<=0)
			throw new ObjectNotFoundException("err_post_not_found");
		Post post=getPostByID(postID, false);
		if(post==null || (onlyLocal && !Config.isLocal(post.activityPubID)))
			throw new ObjectNotFoundException("err_post_not_found");
		return post;
	}

	public static Post getPostByID(int postID, boolean wantDeleted) throws SQLException{
		PreparedStatement stmt=DatabaseConnectionManager.getConnection().prepareStatement("SELECT * FROM wall_posts WHERE id=?");
		stmt.setInt(1, postID);
		try(ResultSet res=stmt.executeQuery()){
			if(res.first()){
				Post post=Post.fromResultSet(res);
				if(post.isDeleted() && !wantDeleted)
					return null;
				return post;
			}
		}
		return null;
	}

	public static Post getPostByID(URI apID) throws SQLException{
		if(Config.isLocal(apID)){
			String[] pathParts=apID.getPath().split("/");
			String posts=pathParts[1];
			int postID=Utils.parseIntOrDefault(pathParts[2], 0);
			if(!"posts".equals(posts) || postID==0){
				throw new ObjectNotFoundException("Invalid local URL "+apID);
			}
			return getPostByID(postID, false);
		}
		PreparedStatement stmt=DatabaseConnectionManager.getConnection().prepareStatement("SELECT * FROM `wall_posts` WHERE `ap_id`=?");
		stmt.setString(1, apID.toString());
		try(ResultSet res=stmt.executeQuery()){
			if(res.first()){
				return Post.fromResultSet(res);
			}
		}
		return null;
	}

	public static void deletePost(int id) throws SQLException{
		Connection conn=DatabaseConnectionManager.getConnection();
		Post post=getPostByID(id, false);
		if(post==null)
			return;
		PreparedStatement stmt;
		boolean needFullyDelete=true;
		if(post.getReplyLevel()>0){
			stmt=conn.prepareStatement("SELECT COUNT(*) FROM wall_posts WHERE reply_key LIKE BINARY bin_prefix(?) ESCAPE CHAR(255)");
			int[] rk=new int[post.replyKey.length+1];
			System.arraycopy(post.replyKey, 0, rk, 0, post.replyKey.length);
			rk[rk.length-1]=post.id;
			stmt.setBytes(1, Utils.serializeIntArray(rk));
			try(ResultSet res=stmt.executeQuery()){
				res.first();
				needFullyDelete=res.getInt(1)==0;
			}
		}

		if(needFullyDelete){
			stmt=conn.prepareStatement("DELETE FROM `wall_posts` WHERE `id`=?");
			stmt.setInt(1, id);
			stmt.execute();
			stmt=conn.prepareStatement("DELETE FROM `newsfeed` WHERE (`type`=0 OR `type`=1) AND `object_id`=?");
			stmt.setInt(1, id);
			stmt.execute();
		}else{
			// (comments don't exist in the feed anyway)
			stmt=conn.prepareStatement("UPDATE wall_posts SET author_id=NULL, owner_user_id=NULL, owner_group_id=NULL, text=NULL, attachments=NULL, content_warning=NULL, updated_at=NULL, mentions=NULL WHERE id=?");
			stmt.setInt(1, id);
			stmt.execute();
		}

		if(post.getReplyLevel()>0){
			conn.createStatement().execute("UPDATE wall_posts SET reply_count=GREATEST(1, reply_count)-1 WHERE id IN ("+Arrays.stream(post.replyKey).mapToObj(String::valueOf).collect(Collectors.joining(","))+")");
		}
	}

	public static Map<Integer, ListAndTotal<Post>> getRepliesForFeed(Set<Integer> postIDs) throws SQLException{
		if(postIDs.isEmpty())
			return Collections.emptyMap();
		Connection conn=DatabaseConnectionManager.getConnection();
		PreparedStatement stmt=conn.prepareStatement(String.join(" UNION ALL ", Collections.nCopies(postIDs.size(), "(SELECT * FROM wall_posts WHERE reply_key=? ORDER BY id DESC LIMIT 3)")));
		int i=0;
		for(int id:postIDs){
			stmt.setBytes(i+1, Utils.serializeIntArray(new int[]{id}));
			i++;
		}
		if(Config.DEBUG)
			System.out.println(stmt);
		HashMap<Integer, ListAndTotal<Post>> map=new HashMap<>();
		try(ResultSet res=stmt.executeQuery()){
			res.afterLast();
			while(res.previous()){
				Post post=Post.fromResultSet(res);
				List<Post> posts=map.computeIfAbsent(post.getReplyChainElement(0), (k)->new ListAndTotal<>(new ArrayList<>(), 0)).list;
				posts.add(post);
			}
		}
		stmt=new SQLQueryBuilder(conn)
				.selectFrom("wall_posts")
				.selectExpr("count(*), reply_key")
				.groupBy("reply_key")
				.whereIn("reply_key", postIDs.stream().map(id->Utils.serializeIntArray(new int[]{id})).collect(Collectors.toList()))
				.createStatement();
		try(ResultSet res=stmt.executeQuery()){
			res.beforeFirst();
			while(res.next()){
				int id=Utils.deserializeIntArray(res.getBytes(2))[0];
				map.get(id).total=res.getInt(1);
			}
		}
		return map;
	}

	public static List<Post> getReplies(int[] prefix) throws SQLException{
		Connection conn=DatabaseConnectionManager.getConnection();
		PreparedStatement stmt=conn.prepareStatement("SELECT * FROM `wall_posts` WHERE `reply_key` LIKE BINARY bin_prefix(?) ESCAPE CHAR(255) ORDER BY `reply_key` ASC, `id` ASC LIMIT 100");
		byte[] replyKey;
		ByteArrayOutputStream b=new ByteArrayOutputStream(prefix.length*4);
		try{
			DataOutputStream o=new DataOutputStream(b);
			for(int id:prefix)
				o.writeInt(id);
		}catch(IOException ignore){}
		replyKey=b.toByteArray();
		stmt.setBytes(1, replyKey);
		ArrayList<Post> posts=new ArrayList<>();
		HashMap<Integer, Post> postMap=new HashMap<>();
		try(ResultSet res=stmt.executeQuery()){
			if(res.first()){
				do{
					Post post=Post.fromResultSet(res);
					postMap.put(post.id, post);
					posts.add(post);
				}while(res.next());
			}
		}
		for(Post post:posts){
			if(post.getReplyLevel()>prefix.length){
				Post parent=postMap.get(post.replyKey[post.replyKey.length-1]);
				if(parent!=null){
					parent.repliesObjects.add(post);
				}
			}
		}
		posts.removeIf(post->post.getReplyLevel()>prefix.length);

		return posts;
	}

	public static List<Post> getRepliesExact(int[] replyKey, int maxID, int limit, int[] total) throws SQLException{
		Connection conn=DatabaseConnectionManager.getConnection();
		if(total!=null){
			PreparedStatement stmt=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.count()
					.where("reply_key=? AND id<?", Utils.serializeIntArray(replyKey), maxID)
					.createStatement();
			total[0]=DatabaseUtils.oneFieldToInt(stmt.executeQuery());
		}
		PreparedStatement stmt=new SQLQueryBuilder(conn)
				.selectFrom("wall_posts")
				.allColumns()
				.where("reply_key=? AND id<?", Utils.serializeIntArray(replyKey), maxID)
				.limit(limit, 0)
				.orderBy("id ASC")
				.createStatement();
		try(ResultSet res=stmt.executeQuery()){
			ArrayList<Post> posts=new ArrayList<>();
			res.beforeFirst();
			while(res.next()){
				posts.add(Post.fromResultSet(res));
			}
			return posts;
		}
	}

	public static URI getActivityPubID(int postID) throws SQLException{
		Connection conn=DatabaseConnectionManager.getConnection();
		PreparedStatement stmt=conn.prepareStatement("SELECT `ap_id`,`owner_user_id` FROM `wall_posts` WHERE `id`=?");
		stmt.setInt(1, postID);
		try(ResultSet res=stmt.executeQuery()){
			if(res.first()){
				if(res.getString(1)!=null)
					return URI.create(res.getString(1));
				return Config.localURI("/posts/"+postID);
			}
		}
		return null;
	}

	public static int getOwnerForPost(int postID) throws SQLException{
		Connection conn=DatabaseConnectionManager.getConnection();
		PreparedStatement stmt=conn.prepareStatement("SELECT `owner_user_id` FROM `wall_posts` WHERE `id`=?");
		stmt.setInt(1, postID);
		try(ResultSet res=stmt.executeQuery()){
			if(res.first())
				return res.getInt(1);
		}
		return 0;
	}

	public static int getLocalPostCount(boolean comments) throws SQLException{
		Connection conn=DatabaseConnectionManager.getConnection();
		try(ResultSet res=conn.createStatement().executeQuery("SELECT COUNT(*) FROM `wall_posts` WHERE `ap_id` IS NULL AND `reply_key` IS "+(comments ? "NOT " : "")+"NULL")){
			res.first();
			return res.getInt(1);
		}
	}

	public static HashMap<Integer, UserInteractions> getPostInteractions(Collection<Integer> postIDs, int userID) throws SQLException{
		HashMap<Integer, UserInteractions> result=new HashMap<>();
		if(postIDs.isEmpty())
			return result;
		for(int id:postIDs)
			result.put(id, new UserInteractions());
		String idsStr=postIDs.stream().map(Object::toString).collect(Collectors.joining(","));

		Connection conn=DatabaseConnectionManager.getConnection();
		try(ResultSet res=conn.createStatement().executeQuery("SELECT object_id, COUNT(*) FROM likes WHERE object_type=0 AND object_id IN ("+idsStr+") GROUP BY object_id")){
			if(res.first()){
				do{
					result.get(res.getInt(1)).likeCount=res.getInt(2);
				}while(res.next());
			}
		}
		if(userID!=0){
			PreparedStatement stmt=conn.prepareStatement("SELECT object_id FROM likes WHERE object_type=0 AND object_id IN ("+idsStr+") AND user_id=?");
			stmt.setInt(1, userID);
			try(ResultSet res=stmt.executeQuery()){
				if(res.first()){
					do{
						result.get(res.getInt(1)).isLiked=true;
					}while(res.next());
				}
			}
		}

		try(ResultSet res=conn.createStatement().executeQuery("SELECT id, reply_count FROM wall_posts WHERE id IN ("+idsStr+")")){
			res.beforeFirst();
			while(res.next()){
				result.get(res.getInt(1)).commentCount=res.getInt(2);
			}
		}

		return result;
	}

	public static List<URI> getInboxesForPostInteractionForwarding(Post post) throws SQLException{
		// Interaction on a top-level post:
		// - local: send to everyone who replied + the post's original addressees (followers + mentions if any)
		// - remote: send to the owner server only. It forwards as it pleases.
		// On a comment: do all of the above for the parent top-level post, and
		// - local: send to any mentioned users
		// - remote: send to the owner server, if not sent already if the parent post is local
		ArrayList<URI> inboxes=new ArrayList<>();
		Post origPost=post;
		if(post.getReplyLevel()>0){
			post=getPostByID(post.replyKey[0], false);
			if(post==null)
				return Collections.emptyList();
		}
		if(post.user instanceof ForeignUser && origPost.getReplyLevel()==0){
			return Collections.singletonList(((ForeignUser) post.user).inbox);
		}
		Connection conn=DatabaseConnectionManager.getConnection();
		ArrayList<String> queryParts=new ArrayList<>();
		if(post.local){
			queryParts.add("SELECT owner_user_id FROM wall_posts WHERE reply_key LIKE BINARY bin_prefix(?) ESCAPE CHAR(255)");
			if(post.owner instanceof ForeignUser)
				queryParts.add("SELECT "+((ForeignUser)post.owner).id);
			else if(post.owner instanceof User)
				queryParts.add("SELECT follower_id FROM followings WHERE followee_id="+((User)post.owner).id);
			else if(post.owner instanceof ForeignGroup)
				inboxes.add(Objects.requireNonNullElse(post.owner.sharedInbox, post.owner.inbox));
			else if(post.owner instanceof Group)
				queryParts.add("SELECT user_id FROM group_members WHERE group_id="+((Group)post.owner).id);

			if(post.mentionedUsers!=null && !post.mentionedUsers.isEmpty()){
				for(User user:post.mentionedUsers){
					if(user instanceof ForeignUser)
						queryParts.add("SELECT "+user.id);
				}
			}
		}else{
			queryParts.add("SELECT "+post.user.id);
		}
		if(origPost!=post){
			if(origPost.local){
				if(origPost.mentionedUsers!=null && !origPost.mentionedUsers.isEmpty()){
					for(User user:origPost.mentionedUsers){
						if(user instanceof ForeignUser)
							queryParts.add("SELECT "+user.id);
					}
				}
			}else{
				queryParts.add("SELECT "+origPost.user.id);
			}
		}
		PreparedStatement stmt=conn.prepareStatement("SELECT DISTINCT IFNULL(ap_shared_inbox, ap_inbox) FROM users WHERE id IN (" +
				String.join(" UNION ", queryParts) +
				") AND ap_inbox IS NOT NULL");
		if(post.local)
			stmt.setBytes(1, Utils.serializeIntArray(new int[]{post.id}));
		try(ResultSet res=stmt.executeQuery()){
			if(res.first()){
				do{
					URI uri=URI.create(res.getString(1));
					if(!inboxes.contains(uri))
						inboxes.add(uri);
				}while(res.next());
			}
		}

		return inboxes;
	}

	public static List<URI> getImmediateReplyActivityPubIDs(int[] replyKey, int offset, int count, int[] total) throws SQLException{
		Connection conn=DatabaseConnectionManager.getConnection();
		byte[] serializedKey=Utils.serializeIntArray(replyKey);
		PreparedStatement stmt=conn.prepareStatement("SELECT count(*) FROM wall_posts WHERE reply_key=?");
		stmt.setBytes(1, serializedKey);
		try(ResultSet res=stmt.executeQuery()){
			res.first();
			total[0]=res.getInt(1);
		}
		stmt=conn.prepareStatement("SELECT ap_id, id FROM wall_posts WHERE reply_key=? ORDER BY created_at ASC LIMIT ?,?");
		stmt.setBytes(1, serializedKey);
		stmt.setInt(2, offset);
		stmt.setInt(3, count);
		try(ResultSet res=stmt.executeQuery()){
			if(res.first()){
				ArrayList<URI> replies=new ArrayList<>();
				do{
					String apID=res.getString(1);
					if(apID!=null)
						replies.add(URI.create(apID));
					else
						replies.add(Config.localURI("/posts/"+res.getInt(2)));
				}while(res.next());
				return replies;
			}
			return Collections.emptyList();
		}
	}
}
