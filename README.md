### Batch job to get historical candles

Dedicated project for the batch job; faster startup, fewer dependencies.

#### Build

`.\mvnw clean package -DskipTests=true`

#### Run

`java -jar .\target\get-candles-historical-batch-0.0.1-SNAPSHOT.jar --spring.profiles.active=local`


#### Cron Jobs

| Schedule   | When                     |
 |------------|--------------------------|
| @reboot    | 60 seconds after startup |
| 0 17 * * * | Daily at 5:00 PM         |

Output is logged to: /home/max/dev/projects/get-candles-historical-batch/cron.log

Useful commands:
- `crontab -l` — view your cron jobs
- `crontab -e` — edit cron jobs
- `crontab -r` — remove your crontab
- `tail -f /home/max/dev/projects/get-candles-historical-batch/cron.log` — watch logs

User crontabs are stored at:

* `/var/spool/cron/crontabs/<username>`
* `/var/spool/cron/crontabs/max`

You shouldn't edit this file directly. Use these commands above instead.
