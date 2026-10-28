# allure-jira-bridge

Посредник между Allure TestOps и Jira (jira.asgardia.uz).

Встроенная Jira-интеграция Allure не умеет отправлять обязательные array-поля `customfield_10200`
(Developer) и `labels`, поэтому Jira отклоняет создание задачи. Сервис стоит вместо адреса Jira в
интеграции Allure и дописывает эти поля в запрос «создать задачу». Остальное делает сам Allure:
QA выбирают проект и тип задачи в форме дефекта, Allure получает ключ задачи и прикрепляет его к дефекту.

```
Allure (форма дефекта: проект, тип) ──► allure-jira-bridge ──► jira.asgardia.uz
                                         │
                                         ├─ POST /rest/api/2/issue: если нет Developer → [{"name":"<assignee>"}],
                                         │                          без assignee        → [{"name":"i.fedulaev"}],
                                         │                          если нет Labels    → ["QA"]
                                         ├─ остальные /rest/** → в Jira без изменений (токен Allure проходит насквозь)
                                         └─ /browse/QDH-1 и т.п. → 302 на настоящую Jira
```

- Значения, которые в запросе уже есть, не заменяются.
- Developer = исполнитель (Assignee) задачи. Если исполнителя нет или выбран «Automatic», ставится
  `JIRA_DEVELOPER_USERNAME` (i.fedulaev).
- Поля добавляются в любом проекте, который выбрали в Allure: список проектов не проверяется. Если в проекте
  поля Developer нет на экране создания, Jira откажет («Field 'customfield_10200' cannot be set...»), задача
  не создастся, и QA увидят эту ошибку в форме дефекта.
- Если QA уберут блок Issue в форме дефекта, задача не создаётся: запроса в Jira просто нет.
- Ошибки Jira возвращаются в Allure как есть, QA видят их прямо в форме.
- У сервиса нет своего токена Jira, базы данных и состояния.

Проверено 2026-10-12 на настоящей Jira через локальный запуск: задача QDH-120 создана запросом без
Developer и Labels, в Jira сохранились Developer = i.fedulaev и Labels = QA. Также проходят без
изменений `myself`, `createmeta` (gzip) и `search` с кавычками в JQL.

Проверено 2026-10-13 со всей цепочкой Allure → ngrok → сервис → Jira: в проекте For Test созданы
дефекты с Create issue (QDH / Task), Allure создал QDH-121 и QDH-122, в Jira заполнены Developer и
Labels, ключи появились в дефектах. Allure создаёт задачу запросом `POST /rest/api/2/issue`,
Test connection — это `GET /rest/api/2/myself`. В настройках интеграции нужна вкладка **Token**:
вкладка Basic отправляет логин и пароль.

## Запуск

Java 17, Maven 3.9.

```bash
mvn package
java -jar target/allure-jira-bridge-0.1.0.jar
```

| Переменная | По умолчанию | Что это |
|---|---|---|
| `PORT` | `8080` | |
| `JIRA_URL` | `https://jira.asgardia.uz` | настоящая Jira |
| `PATH_SECRET` | **обязательна** | секрет в пути: Allure ходит на `https://<сервис>/<PATH_SECRET>/rest/...`, без него 404. Не меньше 32 символов `[A-Za-z0-9_-]`, например `openssl rand -hex 32` |
| `JIRA_DEVELOPER_FIELD` | `customfield_10200` | поле Developer (multi user picker) |
| `JIRA_DEVELOPER_USERNAME` | `i.fedulaev` | логин для Developer, если у задачи нет исполнителя (IgorFedulaev) |
| `JIRA_LABELS` | `QA` | через запятую |
| `MAX_CREATE_ISSUE_BODY` | `1MB` | предел тела «создать задачу» (оно читается в память), больше → 413. Остальные запросы, включая вложения, идут потоком |

Проверка: `GET /actuator/health` → `{"status":"UP"}`.

## Требования к серверу

- **Публичный HTTPS-адрес.** Allure облачный (smartcast.testops.cloud) и должен достучаться до сервиса.
- **Только HTTPS и доверенный сервер.** Через сервис проходит токен Jira, который хранится в Allure.
  Сервис токен не логирует и не хранит.
- **Секрет в пути (`PATH_SECRET`)** закрывает сервис от всех, кто его не знает: иначе это открытый вход в Jira из интернета.
  Секрет виден в ссылках на задачи в Allure и в логах ingress, поэтому это не замена токену, а защита от случайных запросов и подбора.
- Одного экземпляра достаточно. Состояния нет, поэтому можно и несколько.

## Подключение к Allure (безопасный порядок)

Делает администратор Allure (Ruslan).

1. Развернуть сервис и проверить `https://<сервис>/actuator/health`.
2. **Administration → Integrations → + → Jira Server (DC)**. Создать **новую** интеграцию, например
   «Jira via bridge», с адресом `https://<сервис>/<PATH_SECRET>`. Старую «Jira Smartcast» не трогать.
3. Подключить новую интеграцию к тестовому проекту **For Test (235)**:
   Project → Settings → Integrations, указать тот же токен Jira, нажать Test connection.
4. В For Test создать дефект, в блоке Issue выбрать QDH и Task. Ожидаем: задача создана,
   ключ появился в дефекте, в Jira заполнены Developer и Labels. В логе сервиса будет строка
   `Create issue in ... (added [...]) -> 201`.
5. Если всё работает, подключить новую интеграцию к Smartcast Cabinet и отключить там старую.
   Откат: включить старую обратно.

## Ограничения и риски

- Задачи из Allure можно создавать только в проектах, где поле Developer есть на экране создания
  (сейчас QDH и FDH). В других проектах Jira откажет.
- Developer берётся из исполнителя. Если исполнителя нет, Developer один для всех проектов (`i.fedulaev`).
- Токен Jira в Allure сейчас выписан на `i.fedulaev`, у которого права администратора Jira.
  Задачи создаются от его имени.
- Сервис ловит только `POST /rest/api/2/issue` и `/rest/api/latest/issue`. Если Allure создаёт задачи
  другим запросом, поля не добавятся, и в логе не будет строки `Create issue`. Это станет видно на шаге 4.
