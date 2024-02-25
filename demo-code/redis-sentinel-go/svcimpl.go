/**
* Generated by go-doudou v2.3.0.
* You can edit it as your need.
 */
package service

import (
	"context"
	"redis-sentinel-go/config"
	"redis-sentinel-go/dto"

	"github.com/brianvoe/gofakeit/v6"
)

var _ RedisSentinelGo = (*RedisSentinelGoImpl)(nil)

type RedisSentinelGoImpl struct {
	conf *config.Config
}

func (receiver *RedisSentinelGoImpl) PostUser(ctx context.Context, user dto.GddUser) (data int32, err error) {
	var _result struct {
		Data int32
	}
	_ = gofakeit.Struct(&_result)
	return _result.Data, nil
}
func (receiver *RedisSentinelGoImpl) GetUser_Id(ctx context.Context, id int32) (data dto.GddUser, err error) {
	var _result struct {
		Data dto.GddUser
	}
	_ = gofakeit.Struct(&_result)
	return _result.Data, nil
}
func (receiver *RedisSentinelGoImpl) PutUser(ctx context.Context, user dto.GddUser) (re error) {
	var _result struct {
	}
	_ = gofakeit.Struct(&_result)
	return nil
}
func (receiver *RedisSentinelGoImpl) DeleteUser_Id(ctx context.Context, id int32) (re error) {
	var _result struct {
	}
	_ = gofakeit.Struct(&_result)
	return nil
}
func (receiver *RedisSentinelGoImpl) GetUsers(ctx context.Context, parameter dto.Parameter) (data dto.Page, err error) {
	var _result struct {
		Data dto.Page
	}
	_ = gofakeit.Struct(&_result)
	return _result.Data, nil
}

func NewRedisSentinelGo(conf *config.Config) *RedisSentinelGoImpl {
	return &RedisSentinelGoImpl{
		conf: conf,
	}
}
