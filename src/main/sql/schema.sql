create database bitcoindb_tutorial;
use  bitcoindb_tutorial;

create table headers(
	id int not null auto_increment primary key,
	hash binary(32) not null,
    header binary(80) not null,
    height int,
    pow binary(32)
);
create unique index i_headers_hash on headers(hash);

create table properties(
	name varchar(40) not null primary key,
    value varchar(80) not null
);
