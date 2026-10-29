@echo off
rem ?? Go 1.20+??? exe????????????
go-winres simply --icon ..\web\FlyTV.ico --manifest gui --product-name FlyTV --file-version 1.0.49.0 --product-version 1.0.49.0
go build -ldflags "-H windowsgui -s -w" -o FlyTV.exe .

