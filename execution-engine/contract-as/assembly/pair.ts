export class Pair<T1, T2> {
    public first: T1;
    public second: T2;

    constructor(first: T1, second: T2) {
        this.first = first;
        this.second = second;
    }

    @operator("==")
    equalsTo(other: Pair<T1, T2>): bool {
        return this.first == other.first && this.second == other.second;
    }

    @operator("!=")
    notEqualsTo(other: Pair<T1, T2>): bool {
        return !this.equalsTo(other);
    }
}
