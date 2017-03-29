# twitter-wall

App can handle multiple tags and do it efficiently

![one tag](https://raw.githubusercontent.com/KirillTim/twitter-wall/master/screenshots/one%20tag.png)

![two tags](https://raw.githubusercontent.com/KirillTim/twitter-wall/master/screenshots/two%20tags.png)

### I'm totally not a frontend developer, so UI is pretty poor. Sorry

## how to run

1. `mvn clean package`. JAR will be in `/target`
2. Save your twitter auth token in `config.json`
3. Move `config.json` to the same directory where JAR is located
5. Run `java -jar twitter-wall-1.0-SNAPSHOT.jar -conf config.json`
6. Open `localhost:8080`

## TODO
- add tests
- try to fix frontend

