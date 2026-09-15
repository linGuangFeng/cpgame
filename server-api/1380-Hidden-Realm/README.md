# Hidden Realm 1380 controller

Contract v3. Demo reads pre-generated complete rounds from Redis `192.168.10.3:6379/15`.
It never deals at runtime and never reads fixtures.

```
java -jar dist/controller.jar --config dist/controller.properties --port 51380 --publish ..\..\publish\1380-Hidden-Realm
```

Port must be injected in `50000-59999`.
