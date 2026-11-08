package org.sirius.biotech.data;

import java.util.List;

/** Read-only event data seam; implementations own data access and authentication. */
public interface EventRepository {
    List<Session> sessions();
    List<Person> people();
    List<Post> posts();
    List<Partner> partners();
    boolean authenticate(String code);
    boolean demoMode();
    String apiStatus();

    /** Display values, not a confirmed event timetable. */
    public static final class Session {
        public final String id;
        public final String title;
        public final String time;
        public final String room;
        public final String track;
        public final String speaker;
        public final String description;

        public Session(String id, String title, String time, String room,
                       String track, String speaker, String description) {
            this.id = id;
            this.title = title;
            this.time = time;
            this.room = room;
            this.track = track;
            this.speaker = speaker;
            this.description = description;
        }
    }

    public static final class Person {
        public final String id;
        public final String name;
        public final String role;
        public final String organization;

        public final String avatarUrl;
        public final boolean archive;
        public Person(String id,String name,String role,String organization){this(id,name,role,organization,"",false);}
        public Person(String id, String name, String role, String organization,String avatarUrl,boolean archive) {
            this.avatarUrl=avatarUrl;this.archive=archive;
            this.id = id;
            this.name = name;
            this.role = role;
            this.organization = organization;
        }
    }

    public static final class Post {
        public final String id;
        public final String date;
        public final String url;
        public final String title;
        public final String body;

        public Post(String title, String body) {
            this("",title,body,"","");
        }
        public Post(String id,String title,String body,String date,String url) {
            this.id=id;this.title=title;this.body=body;this.date=date;this.url=url;
        }
    }

    public static final class Partner {
        public final String name;
        public final String description;
        public final String id, date, url;

        public Partner(String name, String description) {
            this("",name,description,"","");
        }
        public Partner(String id,String name,String description,String date,String url) {
            this.id=id;this.date=date;this.url=url;
            this.name = name;
            this.description = description;
        }
    }
}
