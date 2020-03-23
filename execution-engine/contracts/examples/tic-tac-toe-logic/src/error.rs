#[derive(PartialEq, Eq, Debug)]
pub enum Error {
    NotActivePlayer,
    InvalidPosition,
    PositionAlreadyFilled,
}
