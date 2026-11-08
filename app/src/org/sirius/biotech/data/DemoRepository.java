package org.sirius.biotech.data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** In-memory fixtures only: no network, persistence, or real account access. */
public final class DemoRepository implements EventRepository {
    private final List<Person> people = Collections.unmodifiableList(Arrays.asList(
            new Person("demo-person-1", "Mira Levin (Demo)", "Genomics researcher (Demo)", "Demo Genome Lab"),
            new Person("demo-person-2", "Anton Valev (Demo)", "Cell engineer (Demo)", "Demo Cell Studio"),
            new Person("demo-person-3", "Nina Belova (Demo)", "Bioprocess scientist (Demo)", "Demo Biofoundry"),
            new Person("demo-person-4", "Lev Orlov (Demo)", "Computational biologist (Demo)", "Demo Data Lab"),
            new Person("demo-person-5", "Sofia Marin (Demo)", "Translational researcher (Demo)", "Demo Therapy Lab"),
            new Person("demo-person-6", "Denis Rovin (Demo)", "Biosafety specialist (Demo)", "Demo Safety Studio")
    ));

    private final List<Session> sessions = Collections.unmodifiableList(Arrays.asList(
            new Session("archive2026-01", "Пленарная сессия", "2026-05-20 · 09:30-11:30", "Зал пленарных заседаний", "ARCHIVE 2026 / Архив 2026", "Rudolf Valenta", "ARCHIVE 2026 / Архив 2026. Preliminary content for 2027; not a confirmed 2027 session. Source: https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf (PDF p. 2)."),
            new Session("archive2026-02", "Прорывы года в России: биотехнологические препараты", "2026-05-20 · 12:00-13:30", "Атом", "ARCHIVE 2026 / Архив 2026", "Speakers: see archival programme / Спикеры: см. программу 2026", "ARCHIVE 2026 / Архив 2026. Preliminary content for 2027; not a confirmed 2027 session. Source: https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf (PDF p. 5)."),
            new Session("archive2026-03", "Прорывы года в России: малые молекулы", "2026-05-20 · 12:00-13:30", "Зима", "ARCHIVE 2026 / Архив 2026", "Speakers: see archival programme / Спикеры: см. программу 2026", "ARCHIVE 2026 / Архив 2026. Preliminary content for 2027; not a confirmed 2027 session. Source: https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf (PDF p. 6)."),
            new Session("archive2026-04", "Прорывы года в России: системы доставки", "2026-05-20 · 12:00-13:30", "Вектор", "ARCHIVE 2026 / Архив 2026", "Speakers: see archival programme / Спикеры: см. программу 2026", "ARCHIVE 2026 / Архив 2026. Preliminary content for 2027; not a confirmed 2027 session. Source: https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf (PDF p. 7)."),
            new Session("archive2026-05", "Тренды: мРНК-препараты", "2026-05-20 · 14:30-16:00", "Молекула", "ARCHIVE 2026 / Архив 2026", "Speakers: see archival programme / Спикеры: см. программу 2026", "ARCHIVE 2026 / Архив 2026. Preliminary content for 2027; not a confirmed 2027 session. Source: https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf (PDF p. 5)."),
            new Session("archive2026-06", "Регуляторные требования к разработке лекарственных препаратов", "2026-05-20 · 14:30-16:00", "Атом", "ARCHIVE 2026 / Архив 2026", "Speakers: see archival programme / Спикеры: см. программу 2026", "ARCHIVE 2026 / Архив 2026. Preliminary content for 2027; not a confirmed 2027 session. Source: https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf (PDF p. 8)."),
            new Session("archive2026-07", "Оценка критических показателей качества биотехнологических продуктов: интерпретация данных аналитических исследований", "2026-05-21 · 10:00-11:30", "Молекула", "ARCHIVE 2026 / Архив 2026", "Speakers: see archival programme / Спикеры: см. программу 2026", "ARCHIVE 2026 / Архив 2026. Preliminary content for 2027; not a confirmed 2027 session. Source: https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf (PDF p. 5)."),
            new Session("archive2026-08", "Современные подходы к проведению доклинических исследований", "2026-05-21 · 10:00-11:30", "Геном", "ARCHIVE 2026 / Архив 2026", "Speakers: see archival programme / Спикеры: см. программу 2026", "ARCHIVE 2026 / Архив 2026. Preliminary content for 2027; not a confirmed 2027 session. Source: https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf (PDF p. 3)."),
            new Session("archive2026-09", "AI и математическое моделирование в разработке лекарств", "2026-05-21 · 10:00-11:30", "Атом", "ARCHIVE 2026 / Архив 2026", "Speakers: see archival programme / Спикеры: см. программу 2026", "ARCHIVE 2026 / Архив 2026. Preliminary content for 2027; not a confirmed 2027 session. Source: https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf (PDF p. 3)."),
            new Session("archive2026-10", "Имплантируемые лекарственные формы", "2026-05-21 · 10:00-11:30", "Вектор", "ARCHIVE 2026 / Архив 2026", "Speakers: see archival programme / Спикеры: см. программу 2026", "ARCHIVE 2026 / Архив 2026. Preliminary content for 2027; not a confirmed 2027 session. Source: https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf (PDF p. 3)."),
            new Session("archive2026-11", "Новые способы введения лекарственных препаратов", "2026-05-22 · 10:00-11:30", "Геном", "ARCHIVE 2026 / Архив 2026", "Speakers: see archival programme / Спикеры: см. программу 2026", "ARCHIVE 2026 / Архив 2026. Preliminary content for 2027; not a confirmed 2027 session. Source: https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf (PDF p. 4)."),
            new Session("archive2026-12", "Разработка биопроцессов", "2026-05-22 · 12:00-13:30", "Вектор", "ARCHIVE 2026 / Архив 2026", "Speakers: see archival programme / Спикеры: см. программу 2026", "ARCHIVE 2026 / Архив 2026. Preliminary content for 2027; not a confirmed 2027 session. Source: https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf (PDF p. 4).")
    ));

    private final List<Post> posts = Collections.unmodifiableList(Arrays.asList(
            new Post("2027 event: dates TBD",
                    "Event year: 2027. Dates TBD. This offline demo does not announce confirmed dates or a timetable."),
            new Post("Explore the demo programme",
                    "Programme uses verified archival sessions from 20-22 May 2026, not confirmed 2027 sessions. Participant profiles and partner cards remain fictional fixtures."),
            new Post("Offline demo access",
                    "Use demo code 123456 to try local authentication. No account is created and no data is sent to a server.")
    ));

    private final List<Partner> partners = Collections.unmodifiableList(Arrays.asList(
            new Partner("Demo Genome Lab", "Fictional demo partner for genomics research; not an actual event partner."),
            new Partner("Demo Biofoundry", "Fictional demo partner for biomanufacturing; not an actual event partner."),
            new Partner("Demo Data Lab", "Fictional demo partner for computational biology; not an actual event partner.")
    ));

    public DemoRepository() {
    }

    @Override
    public List<Session> sessions() {
        return sessions;
    }

    @Override
    public List<Person> people() {
        return people;
    }

    @Override
    public List<Post> posts() {
        return posts;
    }

    @Override
    public List<Partner> partners() {
        return partners;
    }

    @Override
    public boolean authenticate(String code) {
        return "123456".equals(code);
    }

    @Override
    public boolean demoMode() {
        return true;
    }

    @Override
    public String apiStatus() {
        return "Offline demo: no backend connected. Event year: 2027; dates TBD.";
    }
}
