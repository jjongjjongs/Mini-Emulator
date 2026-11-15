// Mini Emulator: an in-memory stand-in for wfc-server's PostgreSQL database, for the
// friends-only room a host phone runs (Wii 친구끼리). The room lives as long as the host's
// game: profiles, friend info, records and ghosts are kept in memory, shared by every
// Connection in the process, and gone when the server stops. Same exported API as the
// upstream database package, so the rest of the server is unchanged.
//
// Modified from WiiLink WFC's wfc-server (https://github.com/WiiLink24/wfc-server),
// AGPL-3.0; this file is under the same license.
package database

import (
	"errors"
	"fmt"
	"math/rand"
	"sort"
	"sync"
	"time"
	"wwfc/common"
	"wwfc/logging"

	"github.com/logrusorgru/aurora/v3"
)

const (
	SakeFieldTypeByte          = 0
	SakeFieldTypeShort         = 1
	SakeFieldTypeInt           = 2
	SakeFieldTypeFloat         = 3
	SakeFieldTypeAsciiString   = 4
	SakeFieldTypeUnicodeString = 5
	SakeFieldTypeBoolean       = 6
	SakeFieldTypeDateAndTime   = 7
	SakeFieldTypeBinaryData    = 8
	SakeFieldTypeInt64         = 9
)

type SakeFieldType int

type SakeField struct {
	Type  SakeFieldType `json:"type"`
	Value string        `json:"value"`
}

type SakeRecord struct {
	GameId   int
	OwnerId  int32
	TableId  string
	RecordId int32
	Fields   map[string]SakeField
}

type User struct {
	ProfileId          uint32
	UserId             uint64
	GsbrCode           string
	NgDeviceId         []uint32
	Email              string
	UniqueNick         string
	FirstName          string
	LastName           string
	Restricted         bool
	RestrictedDeviceId uint32
	BanReason          string
	OpenHost           bool
	LastInGameSn       string
	LastIPAddress      string
	Created            bool
}

type MarioKartWiiTopTenRanking struct {
	Score      int
	PID        int
	PlayerInfo string
}

var (
	// What pgx returned for an empty result; the server compares against it.
	ErrNoRows                 = errors.New("no rows in result set")
	ErrDeviceIDMismatch       = errors.New("NG device ID mismatch")
	ErrProhibitedDeviceID     = errors.New("used prohibited NG device ID in request")
	ErrProfileBannedTOS       = errors.New("profile is banned for violating the Terms of Service")
	ErrProfileIDInUse         = errors.New("profile ID is already in use")
	ErrReservedProfileIDRange = errors.New("profile ID is in reserved range")
	ErrSakeNotOwned           = errors.New("record is not owned by the specified owner ID")
	ErrSakeFieldLimitExceeded = errors.New("record has too many fields")
)

type userKey struct {
	userId uint64
	gsbrcd string
}

type ghost struct {
	id         int
	regionId   int
	courseId   int
	score      int
	pid        int
	playerInfo string
	data       []byte
}

type store struct {
	mu            sync.Mutex
	users         map[uint32]*User
	byKey         map[userKey]uint32
	nextProfileId uint32
	sake          map[int32]*SakeRecord
	nextRecordId  int32
	publicData    map[string]string
	publicTime    map[string]time.Time
	ghosts        []*ghost
	nextGhostId   int
}

var mem = &store{
	users:         map[uint32]*User{},
	byKey:         map[userKey]uint32{},
	nextProfileId: 100000000,
	sake:          map[int32]*SakeRecord{},
	nextRecordId:  1,
	publicData:    map[string]string{},
	publicTime:    map[string]time.Time{},
}

type Connection struct{}

func Start(config common.Config) Connection { return Connection{} }

func (c *Connection) Close() {}

func (c *Connection) UpdateTables() {}

func (c *Connection) InsertEvent(eventType string, eventData map[string]any) (int, error) {
	return 0, nil
}

func (c *Connection) RegisterEvents(config common.Config, eventTypes []string) {}

// ---- users ----

func GetUniqueUserID() uint64 {
	return uint64(rand.Int63n(0x80000000000))
}

func (c *Connection) CreateUser(user *User) error {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	return mem.createUserLocked(user)
}

func (s *store) createUserLocked(user *User) error {
	if user.ProfileId == 0 {
		for s.users[s.nextProfileId] != nil {
			s.nextProfileId++
		}
		user.ProfileId = s.nextProfileId
		s.nextProfileId++
	} else if user.ProfileId >= 1000000000 {
		return ErrReservedProfileIDRange
	} else if s.users[user.ProfileId] != nil {
		return ErrProfileIDInUse
	}
	copied := *user
	s.users[user.ProfileId] = &copied
	s.byKey[userKey{user.UserId, user.GsbrCode}] = user.ProfileId
	return nil
}

func (c *Connection) UpdateProfileID(user *User, newProfileId uint32) error {
	if newProfileId >= 1000000000 {
		return ErrReservedProfileIDRange
	}
	mem.mu.Lock()
	defer mem.mu.Unlock()
	if mem.users[newProfileId] != nil {
		return ErrProfileIDInUse
	}
	stored := mem.users[user.ProfileId]
	if stored == nil {
		return ErrNoRows
	}
	delete(mem.users, user.ProfileId)
	stored.ProfileId = newProfileId
	mem.users[newProfileId] = stored
	mem.byKey[userKey{stored.UserId, stored.GsbrCode}] = newProfileId
	user.ProfileId = newProfileId
	return nil
}

func (c *Connection) UpdateProfile(user *User, data map[string]string) {
	firstName, firstNameExists := data["firstname"]
	lastName, lastNameExists := data["lastname"]
	openHost, openHostExists := data["wl:oh"]
	mem.mu.Lock()
	stored := mem.users[user.ProfileId]
	if stored != nil {
		if firstNameExists {
			stored.FirstName = firstName
		}
		if lastNameExists {
			stored.LastName = lastName
		}
		if openHostExists {
			stored.OpenHost = openHost != "0"
		}
	}
	mem.mu.Unlock()
	if firstNameExists {
		user.FirstName = firstName
	}
	if lastNameExists {
		user.LastName = lastName
	}
	if openHostExists {
		user.OpenHost = openHost != "0"
	}
}

func (c *Connection) GetProfile(profileId uint32) (User, bool) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	stored := mem.users[profileId]
	if stored == nil {
		return User{}, false
	}
	return *stored, true
}

func (c *Connection) ClearProfile(profileId uint32) (User, bool) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	stored := mem.users[profileId]
	if stored == nil {
		return User{}, false
	}
	delete(mem.users, profileId)
	delete(mem.byKey, userKey{stored.UserId, stored.GsbrCode})
	return *stored, true
}

// A room of friends: nobody is banned, and any device ID (Dolphin's default keys too) is fine.
func (c *Connection) BanUser(profileId uint32, tos bool, length time.Duration, reason string, reasonHidden string, moderator string) bool {
	return false
}

func (c *Connection) UnbanUser(profileId uint32) bool { return true }

func (c *Connection) SearchUserBan(profileId uint32, ngDeviceId uint32, ipAddress string, lastIpAddress string) (
	tos bool, issued time.Time, expires time.Time, reason string, bannedProfileId uint32, gsbrCode string, inGameName string, err error) {
	return false, time.Time{}, time.Time{}, "", 0, "", "", errors.New("no ban found")
}

func (c *Connection) LoginUserToGPCM(userId uint64, gsbrcd string, profileId uint32, defaultKey bool, ngDeviceId uint32, ipAddress string, ingamesn string, deviceAuth bool) (User, error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	key := userKey{userId, gsbrcd}
	var user User
	if existing, ok := mem.byKey[key]; ok && mem.users[existing] != nil {
		user = *mem.users[existing]
		user.Created = false
		if profileId != 0 && user.ProfileId != profileId && mem.users[profileId] == nil && profileId < 1000000000 {
			stored := mem.users[user.ProfileId]
			delete(mem.users, user.ProfileId)
			stored.ProfileId = profileId
			mem.users[profileId] = stored
			mem.byKey[key] = profileId
			user.ProfileId = profileId
		}
		logging.Notice("DATABASE", "Log in GPCM user:", aurora.Cyan(userId), aurora.Cyan(gsbrcd), "-", aurora.Cyan(user.ProfileId))
	} else {
		user = User{UserId: userId, GsbrCode: gsbrcd, ProfileId: profileId}
		if ngDeviceId != 0 {
			user.NgDeviceId = []uint32{ngDeviceId}
		}
		user.UniqueNick = common.Base32Encode(userId) + gsbrcd
		user.Email = user.UniqueNick + "@nds"
		// The profile ID the game saved from another server may be taken here: take a new one.
		if user.ProfileId != 0 && (user.ProfileId >= 1000000000 || mem.users[user.ProfileId] != nil) {
			user.ProfileId = 0
		}
		if err := mem.createUserLocked(&user); err != nil {
			return User{}, err
		}
		user.Created = true
		logging.Notice("DATABASE", "Created new GPCM user:", aurora.Cyan(userId), aurora.Cyan(gsbrcd), aurora.Cyan(user.ProfileId))
	}
	if profileId != 0 && user.LastName == "" {
		user.LastName = "000000000" + gsbrcd
		mem.users[user.ProfileId].LastName = user.LastName
	}
	if deviceAuth {
		mem.users[user.ProfileId].LastIPAddress = ipAddress
		mem.users[user.ProfileId].LastInGameSn = ingamesn
	}
	return user, nil
}

func (c *Connection) LoginUserToGameStats(userId uint64, gsbrcd string) (User, error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	if existing, ok := mem.byKey[userKey{userId, gsbrcd}]; ok && mem.users[existing] != nil {
		return *mem.users[existing], nil
	}
	return User{}, ErrNoRows
}

// ---- gamestats ----

func publicKey(profileId uint32, dindex string, ptype string) string {
	return fmt.Sprintf("%d\x00%s\x00%s", profileId, dindex, ptype)
}

func (c *Connection) GetGameStatsPublicData(profileId uint32, dindex string, ptype string) (modifiedTime time.Time, publicData string, err error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	key := publicKey(profileId, dindex, ptype)
	data, ok := mem.publicData[key]
	if !ok {
		return time.Time{}, "", ErrNoRows
	}
	return mem.publicTime[key], data, nil
}

func (c *Connection) CreateGameStatsPublicData(profileId uint32, dindex string, ptype string, publicData string) (modifiedTime time.Time, err error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	key := publicKey(profileId, dindex, ptype)
	now := time.Now().UTC()
	mem.publicData[key] = publicData
	mem.publicTime[key] = now
	return now, nil
}

func (c *Connection) UpdateGameStatsPublicData(profileId uint32, dindex string, ptype string, publicData string) (modifiedTime time.Time, err error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	key := publicKey(profileId, dindex, ptype)
	if _, ok := mem.publicData[key]; !ok {
		return time.Time{}, ErrNoRows
	}
	now := time.Now().UTC()
	mem.publicData[key] = publicData
	mem.publicTime[key] = now
	return now, nil
}

// ---- sake ----

func copyRecord(r *SakeRecord) SakeRecord {
	out := *r
	out.Fields = map[string]SakeField{}
	for k, v := range r.Fields {
		out.Fields[k] = v
	}
	return out
}

func contains(list []int32, v int32) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}

// The filter expression is not applied: the room is small, and the games filter what they get.
func (c *Connection) GetSakeRecords(gameId int, ownerIds []int32, tableId string, recordIds []int32, fields []string, filterExpr string) ([]SakeRecord, error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	var out []SakeRecord
	ids := make([]int, 0, len(mem.sake))
	for id := range mem.sake {
		ids = append(ids, int(id))
	}
	sort.Ints(ids)
	for _, id := range ids {
		r := mem.sake[int32(id)]
		if r.GameId != gameId || r.TableId != tableId {
			continue
		}
		if len(recordIds) > 0 && !contains(recordIds, r.RecordId) {
			continue
		}
		if len(ownerIds) > 0 && !contains(ownerIds, r.OwnerId) {
			continue
		}
		out = append(out, copyRecord(r))
	}
	return out, nil
}

func (c *Connection) UpdateSakeRecord(record SakeRecord, ownerId int32) error {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	r := mem.sake[record.RecordId]
	if r == nil || r.GameId != record.GameId || r.TableId != record.TableId {
		return ErrNoRows
	}
	if ownerId != 0 && r.OwnerId != ownerId {
		return ErrSakeNotOwned
	}
	for k, v := range record.Fields {
		r.Fields[k] = v
	}
	return nil
}

func (c *Connection) InsertSakeRecord(record SakeRecord) (recordId int32, err error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	stored := copyRecord(&record)
	stored.RecordId = mem.nextRecordId
	mem.nextRecordId++
	mem.sake[stored.RecordId] = &stored
	return stored.RecordId, nil
}

func (c *Connection) IsMaxSakeRecordsReached(profileId uint32, maxRecords int) (bool, error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	count := 0
	for _, r := range mem.sake {
		if r.OwnerId == int32(profileId) {
			count++
		}
	}
	return count >= maxRecords, nil
}

// ---- Mario Kart Wii ghosts and rankings (this room's own) ----

func (c *Connection) GetMarioKartWiiTopTenRankings(regionId common.MarioKartWiiLeaderboardRegionId,
	courseId common.MarioKartWiiCourseId) ([]MarioKartWiiTopTenRanking, error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	var list []*ghost
	for _, g := range mem.ghosts {
		if (regionId == 0 || g.regionId == int(regionId)) && g.courseId == int(courseId) {
			list = append(list, g)
		}
	}
	sort.Slice(list, func(i, j int) bool { return list[i].score < list[j].score })
	out := make([]MarioKartWiiTopTenRanking, 0, 10)
	for i := 0; i < len(list) && i < 10; i++ {
		out = append(out, MarioKartWiiTopTenRanking{Score: list[i].score, PID: list[i].pid, PlayerInfo: list[i].playerInfo})
	}
	return out, nil
}

func (c *Connection) GetMarioKartWiiGhostData(courseId common.MarioKartWiiCourseId, time int) (int, error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	var best *ghost
	for _, g := range mem.ghosts {
		if g.courseId == int(courseId) && g.score < time && (best == nil || g.score > best.score) {
			best = g
		}
	}
	if best == nil {
		return 0, ErrNoRows
	}
	return best.id, nil
}

func (c *Connection) GetMarioKartWiiStoredGhostData(regionId common.MarioKartWiiLeaderboardRegionId,
	courseId common.MarioKartWiiCourseId) (int, int, error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	var best *ghost
	for _, g := range mem.ghosts {
		if (regionId == 0 || g.regionId == int(regionId)) && g.courseId == int(courseId) && (best == nil || g.score < best.score) {
			best = g
		}
	}
	if best == nil {
		return 0, 0, ErrNoRows
	}
	return best.pid, best.id, nil
}

func (c *Connection) GetMarioKartWiiFile(fileId int) ([]byte, error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	for _, g := range mem.ghosts {
		if g.id == fileId {
			return g.data, nil
		}
	}
	return nil, ErrNoRows
}

func (c *Connection) GetMarioKartWiiGhostFile(courseId common.MarioKartWiiCourseId, time int, pid int) ([]byte, error) {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	var best *ghost
	for _, g := range mem.ghosts {
		if g.courseId == int(courseId) && g.score < time && g.pid != pid && (best == nil || g.score > best.score) {
			best = g
		}
	}
	if best == nil {
		return nil, ErrNoRows
	}
	return best.data, nil
}

func (c *Connection) InsertMarioKartWiiGhostFile(regionId common.MarioKartWiiLeaderboardRegionId,
	courseId common.MarioKartWiiCourseId, score int, pid int, playerInfo string, data []byte) error {
	mem.mu.Lock()
	defer mem.mu.Unlock()
	for _, g := range mem.ghosts {
		if g.courseId == int(courseId) && g.pid == pid {
			g.regionId, g.score, g.playerInfo, g.data = int(regionId), score, playerInfo, data
			return nil
		}
	}
	mem.nextGhostId++
	mem.ghosts = append(mem.ghosts, &ghost{id: mem.nextGhostId, regionId: int(regionId), courseId: int(courseId), score: score, pid: pid, playerInfo: playerInfo, data: data})
	return nil
}

func (c *Connection) GetMKWFriendInfo(profileId uint32) string {
	records, err := c.GetSakeRecords(1687, []int32{int32(profileId)}, "FriendInfo", nil, []string{"info"}, "")
	if err != nil || len(records) == 0 {
		return ""
	}
	return records[0].Fields["info"].Value
}

func (c *Connection) UpdateMKWFriendInfo(profileId uint32, info string) {
	records, err := c.GetSakeRecords(1687, []int32{int32(profileId)}, "FriendInfo", nil, []string{"info"}, "")
	if err == nil && len(records) > 0 {
		records[0].Fields["info"] = SakeField{Type: SakeFieldTypeBinaryData, Value: info}
		_ = c.UpdateSakeRecord(records[0], int32(profileId))
		return
	}
	_, _ = c.InsertSakeRecord(SakeRecord{GameId: 1687, TableId: "FriendInfo", OwnerId: int32(profileId),
		Fields: map[string]SakeField{"info": {Type: SakeFieldTypeBinaryData, Value: info}}})
}
