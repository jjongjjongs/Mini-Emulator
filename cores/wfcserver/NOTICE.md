# Wii 친구끼리 room server

`libwfcserver.so` in the app is WiiLink WFC's server, [wfc-server](https://github.com/WiiLink24/wfc-server),
built by `cores/build-wfcfriends.sh` with `memdb.go` in place of its PostgreSQL database, so a phone
can run a room for its friends.

- wfc-server is licensed under the **GNU Affero General Public License v3.0**. The server as modified
  here (`memdb.go` and the changes `cores/build-wfcfriends.sh` makes) is under the same license; its
  complete source is this folder, that script and the wfc-server commit it names.
- The client patches and payloads come from [wfc-patcher-wii](https://github.com/WiiLink24/wfc-patcher-wii)
  (custom BSD-style license, attribution required).

Online communications credit to WiiLink WFC - https://wfc.wiilink.ca
